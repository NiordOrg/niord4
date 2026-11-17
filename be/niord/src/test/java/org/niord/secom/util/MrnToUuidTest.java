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
package org.niord.secom.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for {@link MrnToUuid}: the MRN → UUID derivation must stay deterministic and
 * bit-for-bit stable (SECOM consumers key on the dataReference), case-insensitive, and emit
 * well-formed RFC 9562 UUIDv8 values.
 */
public class MrnToUuidTest {

    @Test
    public void derivationIsDeterministicAndCaseInsensitive() {
        UUID first = MrnToUuid.fromMrn("urn:mrn:iho:nw:dk:nw-004-25");
        UUID second = MrnToUuid.fromMrn("urn:mrn:iho:nw:dk:nw-004-25");
        UUID upperCased = MrnToUuid.fromMrn("URN:MRN:IHO:NW:DK:NW-004-25");

        assertEquals(first, second, "same MRN must always yield the same UUID");
        assertEquals(first, upperCased, "the MRN is normalized to lower-case before hashing");
    }

    @Test
    public void distinctMrnsYieldDistinctUuids() {
        assertNotEquals(
                MrnToUuid.fromMrn("urn:mrn:iho:nw:dk:nw-004-25"),
                MrnToUuid.fromMrn("urn:mrn:iho:nw:dk:nw-005-25"));
    }

    @Test
    public void uuidsCarryVersion8AndRfcVariant() {
        UUID uuid = MrnToUuid.fromMrn("urn:mrn:iho:nw:dk:nw-004-25");
        assertEquals(8, uuid.version(), "must be an RFC 9562 UUIDv8");
        assertEquals(2, uuid.variant(), "must carry the RFC variant (0b10)");
    }

    @Test
    public void bitLayoutIsFrozen() {
        // Golden value: SHA-256("urn:mrn:iho:nw:dk:nw-004-25") packed into the UUIDv8 custom bits.
        // If this assertion ever fails, the deployed SECOM ecosystem would see dataReferences
        // change for existing warnings - the bit layout must not be "improved".
        assertEquals("b2eb9314-7029-89eb-af4c-6d0ccdd87bb9",
                MrnToUuid.fromMrn("urn:mrn:iho:nw:dk:nw-004-25").toString());
    }
}
