/*
 * Copyright 2024 Danish Maritime Authority.
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

package org.niord.model.message;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the message value-object enums:
 * {@link MainType}, {@link Type}, {@link Status}, {@link ReferenceType} and
 * {@link MessagePartType}.
 *
 * These enums form part of the REST/JSON API contract and carry small but
 * business-critical pieces of logic (status visibility, NW/NM categorisation,
 * reference cancellation semantics). The tests pin the exact mappings so that a
 * later Spring migration cannot silently change them.
 */
public class MessageEnumsTest {

    // ------------------------------------------------------------------
    // MainType.getTypes()
    // ------------------------------------------------------------------

    @Test
    public void mainTypeNmGetTypes_returnsExactlyTheFourNmTypes() {
        Set<Type> nmTypes = MainType.NM.getTypes();

        assertEquals(4, nmTypes.size());
        Set<Type> expected = EnumSet.of(
                Type.PERMANENT_NOTICE,
                Type.TEMPORARY_NOTICE,
                Type.PRELIMINARY_NOTICE,
                Type.MISCELLANEOUS_NOTICE);
        assertEquals(expected, nmTypes);
    }

    @Test
    public void mainTypeNwGetTypes_returnsExactlyTheFourNwTypes() {
        Set<Type> nwTypes = MainType.NW.getTypes();

        assertEquals(4, nwTypes.size());
        Set<Type> expected = EnumSet.of(
                Type.COASTAL_WARNING,
                Type.SUBAREA_WARNING,
                Type.NAVAREA_WARNING,
                Type.LOCAL_WARNING);
        assertEquals(expected, nwTypes);
    }

    @Test
    public void mainTypeGetTypes_nwAndNmAreDisjointAndCoverAllTypes() {
        Set<Type> nwTypes = MainType.NW.getTypes();
        Set<Type> nmTypes = MainType.NM.getTypes();

        // Disjoint: no Type belongs to both main types.
        Set<Type> intersection = new HashSet<>(nwTypes);
        intersection.retainAll(nmTypes);
        assertTrue(intersection.isEmpty(), "NW and NM type sets must be disjoint");

        // Union covers every declared Type value exactly once.
        Set<Type> union = new HashSet<>(nwTypes);
        union.addAll(nmTypes);
        assertEquals(Type.values().length, union.size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(Type.values())), union);
    }

    // ------------------------------------------------------------------
    // MainType.anyType()
    // ------------------------------------------------------------------

    @Test
    public void mainTypeNmAnyType_returnsAMemberWhoseMainTypeIsNm() {
        Type any = MainType.NM.anyType();

        assertTrue(MainType.NM.getTypes().contains(any),
                "anyType() must be one of NM's own types");
        assertEquals(MainType.NM, any.getMainType());
    }

    @Test
    public void mainTypeNwAnyType_returnsAMemberWhoseMainTypeIsNw() {
        Type any = MainType.NW.anyType();

        assertTrue(MainType.NW.getTypes().contains(any),
                "anyType() must be one of NW's own types");
        assertEquals(MainType.NW, any.getMainType());
    }

    // ------------------------------------------------------------------
    // Type.getMainType() -- exhaustive mapping for all 8 values
    // ------------------------------------------------------------------

    @Test
    public void typeGetMainType_allNmTypesMapToNm() {
        assertEquals(MainType.NM, Type.PERMANENT_NOTICE.getMainType());
        assertEquals(MainType.NM, Type.TEMPORARY_NOTICE.getMainType());
        assertEquals(MainType.NM, Type.PRELIMINARY_NOTICE.getMainType());
        assertEquals(MainType.NM, Type.MISCELLANEOUS_NOTICE.getMainType());
    }

    @Test
    public void typeGetMainType_allNwTypesMapToNw() {
        assertEquals(MainType.NW, Type.COASTAL_WARNING.getMainType());
        assertEquals(MainType.NW, Type.SUBAREA_WARNING.getMainType());
        assertEquals(MainType.NW, Type.NAVAREA_WARNING.getMainType());
        assertEquals(MainType.NW, Type.LOCAL_WARNING.getMainType());
    }

    @Test
    public void typeGetMainType_isNeverNullForAnyValue() {
        for (Type t : Type.values()) {
            MainType mt = t.getMainType();
            assertNotNull(mt, "Every Type must declare a main type: " + t);
            // The main type must claim this type as one of its own.
            assertTrue(mt.getTypes().contains(t),
                    "Main type " + mt + " must contain " + t);
        }
    }

    // ------------------------------------------------------------------
    // Status.isPublic() -- full truth table
    // ------------------------------------------------------------------

    @Test
    public void statusIsPublic_truthTableForEveryValue() {
        // Public: visible to unauthorized users.
        assertTrue(Status.PUBLISHED.isPublic());
        assertTrue(Status.CANCELLED.isPublic());
        assertTrue(Status.EXPIRED.isPublic());

        // Non-public.
        assertFalse(Status.DRAFT.isPublic());
        assertFalse(Status.VERIFIED.isPublic());
        assertFalse(Status.DELETED.isPublic());
    }

    @Test
    public void statusIsPublic_iteratesAllValuesAgainstExpectedSet() {
        Set<Status> expectedPublic = EnumSet.of(
                Status.PUBLISHED, Status.CANCELLED, Status.EXPIRED);
        for (Status s : Status.values()) {
            assertEquals(expectedPublic.contains(s), s.isPublic(),
                    "isPublic() mismatch for " + s);
        }
    }

    // ------------------------------------------------------------------
    // Status.isDraft() -- full truth table
    // ------------------------------------------------------------------

    @Test
    public void statusIsDraft_truthTableForEveryValue() {
        // Draft statuses.
        assertTrue(Status.DRAFT.isDraft());
        assertTrue(Status.VERIFIED.isDraft());

        // Non-draft statuses.
        assertFalse(Status.PUBLISHED.isDraft());
        assertFalse(Status.EXPIRED.isDraft());
        assertFalse(Status.CANCELLED.isDraft());
        assertFalse(Status.DELETED.isDraft());
    }

    @Test
    public void statusIsDraft_iteratesAllValuesAgainstExpectedSet() {
        Set<Status> expectedDraft = EnumSet.of(Status.DRAFT, Status.VERIFIED);
        for (Status s : Status.values()) {
            assertEquals(expectedDraft.contains(s), s.isDraft(),
                    "isDraft() mismatch for " + s);
        }
    }

    @Test
    public void statusIsPublicAndIsDraft_areMutuallyExclusiveButNotExhaustive() {
        // No status is simultaneously public and draft.
        for (Status s : Status.values()) {
            assertFalse(s.isPublic() && s.isDraft(),
                    "Status must not be both public and draft: " + s);
        }
        // DELETED is neither public nor draft -- it falls outside both buckets,
        // which is the intended behaviour (deleted drafts are hidden).
        assertFalse(Status.DELETED.isPublic());
        assertFalse(Status.DELETED.isDraft());
    }

    // ------------------------------------------------------------------
    // ReferenceType.cancelsReferencedMessage()
    // ------------------------------------------------------------------

    @Test
    public void referenceTypeCancelsReferencedMessage_returnsExactlyTheFourCancellingTypes() {
        Set<ReferenceType> cancelling = ReferenceType.cancelsReferencedMessage();

        assertEquals(4, cancelling.size());
        Set<ReferenceType> expected = EnumSet.of(
                ReferenceType.REPETITION,
                ReferenceType.REPETITION_NEW_TIME,
                ReferenceType.CANCELLATION,
                ReferenceType.UPDATE);
        assertEquals(expected, cancelling);
    }

    @Test
    public void referenceTypeCancelsReferencedMessage_containsEachCancellingType() {
        Set<ReferenceType> cancelling = ReferenceType.cancelsReferencedMessage();

        assertTrue(cancelling.contains(ReferenceType.REPETITION));
        assertTrue(cancelling.contains(ReferenceType.REPETITION_NEW_TIME));
        assertTrue(cancelling.contains(ReferenceType.CANCELLATION));
        assertTrue(cancelling.contains(ReferenceType.UPDATE));
    }

    @Test
    public void referenceTypeCancelsReferencedMessage_doesNotContainPlainReference() {
        Set<ReferenceType> cancelling = ReferenceType.cancelsReferencedMessage();

        assertFalse(cancelling.contains(ReferenceType.REFERENCE),
                "A plain REFERENCE must not cancel the referenced message");
    }

    @Test
    public void referenceTypeCancelsReferencedMessage_returnsAFreshMutableSetEachCall() {
        Set<ReferenceType> first = ReferenceType.cancelsReferencedMessage();
        Set<ReferenceType> second = ReferenceType.cancelsReferencedMessage();

        // Distinct instances -- the factory builds a new HashSet each time.
        assertNotSame(first, second);
        // Equal in content.
        assertEquals(first, second);
        // Mutating the returned set must not leak into a subsequent call.
        first.clear();
        assertEquals(4, ReferenceType.cancelsReferencedMessage().size());
    }

    // ------------------------------------------------------------------
    // MessagePartType
    // ------------------------------------------------------------------

    @Test
    public void messagePartType_hasExactlySixValues() {
        assertEquals(6, MessagePartType.values().length);
    }

    @Test
    public void messagePartType_containsAllExpectedConstants() {
        Set<MessagePartType> values =
                EnumSet.copyOf(java.util.Arrays.asList(MessagePartType.values()));

        Set<MessagePartType> expected = EnumSet.of(
                MessagePartType.DETAILS,
                MessagePartType.TIME,
                MessagePartType.POSITIONS,
                MessagePartType.NOTE,
                MessagePartType.PROHIBITION,
                MessagePartType.SIGNALS);
        assertEquals(expected, values);
    }
}
