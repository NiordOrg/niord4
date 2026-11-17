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
package org.niord.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link UidUtils}, covering UID generation, format validation and the
 * hashed-folder-path derivation. These are pure-logic tests with no framework coupling.
 */
public class UidUtilsTest {

    /** A fixed, well-formed UUID string used as a deterministic input across tests. */
    private static final String KNOWN_UID = "12345678-1234-1234-1234-1234567890ab";

    // ---------------------------------------------------------------------
    // newUid()
    // ---------------------------------------------------------------------

    @Test
    public void newUid_producesValidUuidFormat() {
        assertTrue(UidUtils.validUidFormat(UidUtils.newUid()));
    }

    @Test
    public void newUid_producesDistinctValues() {
        // Two consecutive generations must not collide.
        assertNotEquals(UidUtils.newUid(), UidUtils.newUid());
    }

    @Test
    public void newUid_hasCanonical36CharLength() {
        // Canonical UUID string form: 8-4-4-4-12 hex digits plus 4 hyphens = 36 chars.
        assertEquals(36, UidUtils.newUid().length());
    }

    // ---------------------------------------------------------------------
    // validUidFormat(String)
    // ---------------------------------------------------------------------

    @Test
    public void validUidFormat_acceptsWellFormedUuid() {
        assertTrue(UidUtils.validUidFormat(KNOWN_UID));
    }

    @Test
    public void validUidFormat_acceptsUppercaseUuid() {
        // UUID.fromString is case-insensitive on the hex digits.
        assertTrue(UidUtils.validUidFormat("12345678-1234-1234-1234-1234567890AB"));
    }

    @Test
    public void validUidFormat_rejectsNonUuidWord() {
        assertFalse(UidUtils.validUidFormat("not-a-uuid"));
    }

    @Test
    public void validUidFormat_rejectsShortNumericString() {
        assertFalse(UidUtils.validUidFormat("123"));
    }

    @Test
    public void validUidFormat_rejectsEmptyString() {
        assertFalse(UidUtils.validUidFormat(""));
    }

    @Test
    public void validUidFormat_rejectsUuidWithMissingGroups() {
        // Only three dash-separated groups instead of the required five.
        assertFalse(UidUtils.validUidFormat("12345678-1234-1234"));
    }

    @Test
    public void validUidFormat_rejectsNonHexCharacters() {
        // 'g'..'z' are not valid hex digits within a UUID group.
        assertFalse(UidUtils.validUidFormat("zzzzzzzz-1234-1234-1234-1234567890ab"));
    }

    /**
     * A {@code null} uid is simply not a valid UUID, so the validator must return
     * {@code false} rather than throwing. (The method guards null explicitly; without the
     * guard {@code UUID.fromString(null)} throws an NPE that escapes the IAE catch clause.)
     */
    @Test
    public void validUidFormat_null_returnsFalse() {
        assertFalse(UidUtils.validUidFormat(null));
    }

    // ---------------------------------------------------------------------
    // uidToHashedFolderPath(String, String)
    // ---------------------------------------------------------------------

    @Test
    public void uidToHashedFolderPath_buildsNestedHashedPath() {
        // Path = rootPath / uid[0] / uid[1..2] / uid
        assertEquals(
                "root/1/23/12345678-1234-1234-1234-1234567890ab",
                UidUtils.uidToHashedFolderPath("root", KNOWN_UID));
    }

    @Test
    public void uidToHashedFolderPath_usesGeneratedUidPrefix() {
        // The first sub-folder is the first char and the second is chars [1,3).
        String uid = UidUtils.newUid();
        String expected = "messages/" + uid.substring(0, 1) + "/" + uid.substring(1, 3) + "/" + uid;
        assertEquals(expected, UidUtils.uidToHashedFolderPath("messages", uid));
    }

    @Test
    public void uidToHashedFolderPath_nullUidReturnsNull() {
        assertNull(UidUtils.uidToHashedFolderPath("root", null));
    }

    @Test
    public void uidToHashedFolderPath_blankUidReturnsNull() {
        // StringUtils.isBlank treats whitespace-only strings as blank.
        assertNull(UidUtils.uidToHashedFolderPath("root", "   "));
    }

    @Test
    public void uidToHashedFolderPath_emptyUidReturnsNull() {
        assertNull(UidUtils.uidToHashedFolderPath("root", ""));
    }

    @Test
    public void uidToHashedFolderPath_invalidUidThrows() {
        assertThrows(IllegalArgumentException.class, () -> UidUtils.uidToHashedFolderPath("root", "not-a-uuid"));
    }
}
