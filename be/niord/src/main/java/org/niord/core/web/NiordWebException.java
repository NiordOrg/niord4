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
package org.niord.core.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * Spring-native replacement for the JAX-RS {@code jakarta.ws.rs.WebApplicationException} that the ported
 * REST resources used to throw to signal an HTTP error status.
 *
 * <p>It extends {@link ResponseStatusException} (an {@code ErrorResponse}), so when
 * {@code spring.mvc.problemdetails.enabled=true} the auto-configured {@code ResponseEntityExceptionHandler}
 * renders it as an RFC 9457 {@code application/problem+json} body — the {@link #getReason() reason} becomes
 * the ProblemDetail {@code detail}. No custom {@code @ExceptionHandler} is required.
 *
 * <p>The constructors deliberately mirror the argument order of the old {@code WebApplicationException} so the
 * migration is a near-pure rename of the throw sites. The {@code (String, Throwable)} form maps to HTTP 500,
 * matching JAX-RS's default for that constructor while preserving the cause.
 */
public class NiordWebException extends ResponseStatusException {

    /** Throws the given HTTP status code with no detail message. */
    public NiordWebException(int status) {
        super(HttpStatusCode.valueOf(status));
    }

    /** Throws the given HTTP status code with a human-readable detail message. */
    public NiordWebException(String detail, int status) {
        super(HttpStatusCode.valueOf(status), detail);
    }

    /** Wraps the given cause as an HTTP 500 with a detail message (mirrors the JAX-RS default). */
    public NiordWebException(String detail, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, detail, cause);
    }

}
