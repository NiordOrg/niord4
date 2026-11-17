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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Derives a deterministic RFC 9562 UUIDv8 from an MRN, so a navigational warning keeps the
 * same SECOM {@code dataReference} across updates and re-publications.
 * <p>
 * SHA-256 of the lower-cased MRN packed into the UUIDv8 custom bits — the deployed SECOM
 * ecosystem has seen these exact UUIDs, so the bit layout must not change.
 */
public final class MrnToUuid {

    private MrnToUuid() {
    }

    /** Creates a deterministic UUIDv8 from the given MRN (case-insensitive) */
    public static UUID fromMrn(String mrn) {
        // Normalize the MRN
        String normMrn = mrn.toLowerCase();

        // Hash the MRN using SHA-256
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        byte[] hash = digest.digest(normMrn.getBytes(StandardCharsets.UTF_8));

        // A UUIDv8 according to RFC 9562:

        // Most significant bits: custom_a (48 bits) + version (4 bits) + custom_b (12 bits)
        long mostSigBits = ((hash[0] & 0xFFL) << 40) | ((hash[1] & 0xFFL) << 32) | ((hash[2] & 0xFFL) << 24) | ((hash[3] & 0xFFL) << 16)
                | ((hash[4] & 0xFFL) << 8) | (hash[5] & 0xFFL);
        mostSigBits = (mostSigBits << 4) | 0x8; // Add version (4 bits: 0b1000 for UUIDv8)
        mostSigBits = (mostSigBits << 12) | ((hash[6] & 0xFFL) << 4) | ((hash[7] >> 4) & 0xF); // Add custom_b (12 bits)

        // Least significant bits: variant (2 bits) + custom_c (62 bits)
        long leastSigBits = ((hash[7] & 0x0FL) << 60) | ((hash[8] & 0xFFL) << 52) | ((hash[9] & 0xFFL) << 44) | ((hash[10] & 0xFFL) << 36)
                | ((hash[11] & 0xFFL) << 28) | ((hash[12] & 0xFFL) << 20) | ((hash[13] & 0xFFL) << 12) | ((hash[14] & 0xFFL) << 4) | ((hash[15] >> 4) & 0xF);
        leastSigBits &= 0x3FFFFFFFFFFFFFFFL; // Clear variant bits
        leastSigBits |= 0x8000000000000000L; // Set variant to 0b10

        return new UUID(mostSigBits, leastSigBits);
    }
}
