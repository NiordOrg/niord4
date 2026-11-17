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
package org.niord.secom.pki;


/**
 * Persists a renewed PKCS12 keystore to a configured store location - the deployment-specific
 * seam of the unattended certificate renewal (identity plan T3). Two backends ship: the
 * writable-file backend ({@link FileKeystoreWriter}, phase 3) and the k8s Secret-PATCH
 * backend ({@link KubernetesSecretKeystoreWriter}, phase 4).
 */
public interface KeystoreWriter {

    /** {@return whether this backend can persist to the given store location} */
    boolean supports(String location);

    /** Durably replaces the store at the location with the given PKCS12 bytes; atomic per location */
    void write(String location, byte[] keystoreBytes) throws Exception;

    /**
     * Whether a completed {@link #write} becomes readable at the location only later. False
     * for plain files (read-your-write); true for the k8s backend, where the authoritative
     * Secret is updated immediately but the mounted file the application reads converges
     * asynchronously (kubelet sync, ~1 minute), after which the store watch (identity plan
     * T2) completes the in-memory pickup.
     */
    default boolean deferredPickup() {
        return false;
    }
}
