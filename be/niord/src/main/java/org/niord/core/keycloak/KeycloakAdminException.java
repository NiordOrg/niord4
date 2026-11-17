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
package org.niord.core.keycloak;

/**
 * A Keycloak admin request that Keycloak answered with an error status. It carries that status, so a caller
 * can tell a refusal (403) or an unknown user or group (404) from a Keycloak that fails (5xx). A request
 * that got no answer at all (Keycloak down, a refused connection) is not this exception: the admin client
 * throws its own {@code ProcessingException} for it.
 * <p>
 * Its message is the one the callers always logged: "Unable to execute Keycloak admin request, status = NNN".
 */
public class KeycloakAdminException extends Exception {

    private final int status;

    public KeycloakAdminException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** The HTTP status Keycloak answered, or -1 when the answer carried none */
    public int getStatus() {
        return status;
    }
}
