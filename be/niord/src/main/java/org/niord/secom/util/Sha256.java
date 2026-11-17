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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The single SHA-256-to-lower-case-hex rendering used across the SECOM code: certificate
 * fingerprints, keystore source digests, exchange-set payload hashes and the digests logged
 * for a failed signature. Callers that need a different failure policy than the unchecked
 * {@code IllegalStateException} below wrap this call rather than re-deriving the digest.
 */
public final class Sha256 {

    private Sha256() {
    }

    /** {@return the SHA-256 digest of the given bytes as lower-case hex} */
    public static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
