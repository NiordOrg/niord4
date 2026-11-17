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
package org.niord.secom.service;

/**
 * A server-side fault while producing S-124 exchange-set content: persisted GML that no longer
 * unmarshals, a dataset that cannot be marshalled for signing, a JCA/private-key signing
 * failure, or a service certificate that cannot be re-encoded.
 * <p>
 * Deliberately not an {@link IllegalStateException}: {@code RestExceptionStatusAdvice} maps that
 * to {@code 409 Conflict} as a client-state error, whereas none of these faults is something the
 * caller can resolve by retrying, so on the sysadmin endpoints that reach them (the republish
 * chain) they must render as {@code 500}. Unmapped, this exception gets Spring's default
 * server-error rendering; the SECOM protocol resources are JAX-RS and already render it as 500.
 */
public class S124ExchangeSetException extends RuntimeException {

    public S124ExchangeSetException(String message) {
        super(message);
    }

    public S124ExchangeSetException(String message, Throwable cause) {
        super(message, cause);
    }
}
