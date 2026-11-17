/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.web.publication;

import jakarta.persistence.OptimisticLockException;
import org.niord.core.publication.series.StaleVersionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers a publication write that lost a race at the database with the same coded
 * 409 {@code STALE_VERSION} a stale write gets at the revision check.
 *
 * Two saves composed against one revision and arriving together both pass
 * {@link StaleVersionGuard#check}: each reads the same stored revision. The first
 * commit moves it, and the second fails on the {@code @Version} update at flush or
 * commit with an optimistic-lock failure. That is the same conflict the guard
 * refuses a moment later, so it gets the same code; the client branches on the
 * code, re-reads and lets the admin apply the edit again. Nothing was written.
 *
 * The body has the publication shape ({@code code}, {@code message}), not a
 * {@code ProblemDetail}, because the publication client reads it off the object
 * (see {@link PublicationExceptionAdvice}). It carries no {@code storedVersion} or
 * {@code submittedVersion}: neither is known where the failure is caught.
 *
 * SCOPED TO THIS PACKAGE, and that is why this is not a second handler on
 * {@link PublicationExceptionAdvice}: that advice applies to every controller, and
 * its single handler's parameter type is load-bearing (see its Javadoc). Every
 * other controller gets the same 409 as a problem detail from
 * {@code RestExceptionStatusAdvice}. HIGHEST_PRECEDENCE so this advice is asked
 * before that one for the publication controllers.
 */
@RestControllerAdvice(basePackageClasses = PublicationExceptionAdvice.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicationConcurrencyAdvice {

    private static final Logger log = LoggerFactory.getLogger(PublicationConcurrencyAdvice.class);

    /** The sentence of the refusal; the code is what a client acts on. */
    static final String MESSAGE = "Somebody saved this at the same time; reload it and save again";

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<Map<String, Object>> toResponse(RuntimeException e) {
        log.info("publication write lost a concurrent save -> 409 {}: {}",
                StaleVersionGuard.STALE_VERSION, e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", StaleVersionGuard.STALE_VERSION);
        body.put("message", MESSAGE);
        return ResponseEntity.status(PublicationErrorCatalogue.statusOf(StaleVersionGuard.STALE_VERSION))
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
