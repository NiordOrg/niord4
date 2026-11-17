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
package org.niord.core.conf;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.JDBCException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the common validation/domain exceptions escaping {@code @RestController} handlers to their
 * proper 4xx statuses instead of Spring's default {@code 500 Internal Server Error}.
 *
 * <p>Many resources throw {@code NiordWebException} with an explicit status (rendered by the
 * {@code spring.mvc.problemdetails} handler), but a large number of handler-reachable sites report
 * client-input errors with plain {@link IllegalArgumentException} / {@link IllegalStateException}
 * (e.g. {@code MessageCommentRestService}'s "Invalid message ID"). Without a mapping these surface
 * as {@code 500} — an incorrect HTTP contract, indistinguishable from a genuine server fault.
 *
 * <p>Two failures the database reports are mapped by their SQLState: a value longer than its column (400) and the
 * loser of a deadlock (409); every other database failure keeps its 500 ({@link #handleDatabaseRefusal}).
 *
 * <p>The advice only supplies the status; the body is an RFC 9457 {@link ProblemDetail}, matching
 * the {@code spring.mvc.problemdetails.enabled=true} rendering used for the rest of the error
 * handling. Security exceptions are unaffected — {@code AccessDeniedException} and the resource
 * server's authentication failures are handled before this advice by Spring Security's
 * {@code ExceptionTranslationFilter}.
 */
@RestControllerAdvice
public class RestExceptionStatusAdvice {

    static final Logger log = LoggerFactory.getLogger(RestExceptionStatusAdvice.class);

    /** Client-input errors ({@code "Invalid message ID …"}, malformed parameters) → {@code 400}. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Mapping IllegalArgumentException to 400 Bad Request: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Invalid-state errors (illegal status transitions and the like) → {@code 409}. Logged at warn
     * with the stack trace: unlike the routine client-input 400s, an {@code IllegalStateException}
     * can also signal a server-side fault, and before this advice it was stack-traced as a 500.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        log.warn("Mapping IllegalStateException to 409 Conflict: " + ex.getMessage(), ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Missing-entity errors (e.g. a stale {@code em.getReference} proxy) → {@code 404}. */
    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleEntityNotFound(EntityNotFoundException ex) {
        log.warn("Mapping EntityNotFoundException to 404 Not Found: " + ex.getMessage(), ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * A write that lost a race on a {@code @Version} row → {@code 409}. Two saves of one revision
     * arriving together both read the same revision; the first commit moves it and the second
     * fails on the version update at flush or commit. Nothing was written, and the caller's remedy
     * is to reload and save again, which is what the detail says. The exception's own message
     * names Hibernate's row count and the entity, so it is logged, not sent. Logged at info
     * without a stack: this is a client race, not a server fault. The publication controllers
     * answer the same race in their own body shape ({@code PublicationConcurrencyAdvice}).
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ProblemDetail handleOptimisticLock(RuntimeException ex) {
        log.info("Mapping {} to 409 Conflict: {}", ex.getClass().getSimpleName(), ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Somebody saved this at the same time; reload it and save again");
    }

    /**
     * Two failures the database reports, answered by their SQLState anywhere in the cause chain (and behind a batch
     * failure's next exception); every other database failure is left as it was.
     *
     * <ul>
     *   <li>{@value #VALUE_TOO_LONG}, a value longer than its column (PostgreSQL's "value too long for type
     *       character varying(n)") → {@code 400} with the column's limit (ERR7). The editors limit what is typed,
     *       but an API caller, an import or an old browser can still send more. PostgreSQL's sentence names the
     *       type, not the column, so the detail names the limit only.</li>
     *   <li>{@value #DEADLOCK}, the transaction PostgreSQL rolled back to break a deadlock → {@code 409} "try
     *       again". A publication or a cancellation takes the series' number row inside its own transaction (P1-3),
     *       so two of them crossing on a number row and a message row can deadlock; the winner commits, and the
     *       loser's request may simply be sent again.</li>
     * </ul>
     *
     * <p>The failure reaches this advice in one of two forms, depending on where the statement ran (both seen over
     * HTTP by {@code ValueTooLongRefusalsITTest}): Hibernate's own {@link JDBCException} (its {@code DataException}
     * from a flush inside the request, which no {@code @Repository} translation wraps: an area's or a publication
     * category's create), or Spring's translated {@code DataIntegrityViolationException} (a flush at the commit of a
     * {@code @Transactional} controller or service: a dictionary entry, a setting, a category's update). A deadlock
     * arrives as Hibernate's {@code LockAcquisitionException} (a {@code JDBCException}), as a
     * {@code jakarta.persistence.PessimisticLockException} around one, or as Spring's
     * {@code PessimisticLockingFailureException} (its {@code CannotAcquireLockException} at a commit, or a
     * {@code JdbcTemplate} statement such as the number row's lock). Spring offers a handler a failure's cause when
     * no handler names the failure itself, so a wrapper around one of these (a {@code TransactionSystemException}
     * around a {@code RollbackException}, the jakarta {@code PessimisticLockException}) is asked here too.
     *
     * <p>The handler names {@code JDBCException}, which always holds an {@link SQLException}, and not
     * {@code jakarta.persistence.PersistenceException}, the base of nearly every JPA failure. Spring picks a handler
     * by the thrown type before it looks at the causes, and a failure this handler throws on is not offered to any
     * handler again. Named here, a {@code PersistenceException} around a domain failure (Hibernate wraps whatever an
     * {@code AttributeConverter} throws, a publication's {@code CriteriaParseException} among them) would reach this
     * handler, be thrown on, and lose the answer its cause has: the 400, 404 or 409 above, or the publication
     * advice's coded refusal.
     *
     * <p>Every other failure of these types (a unique or foreign key, PROM8 and CONTACT3 among them; a lock that was
     * not free; anything with no SQLState) is thrown on unchanged: Spring then goes on with the failure as if no
     * handler had matched, and the container answers 500, as before. The more specific handlers above (the optimistic
     * lock, the missing entity) still win for their own types.
     *
     * <p>The database's sentence can hold the statement and its values, so it is logged and never sent. The log line
     * holds the wrapper's message too, which names the statement (and so the table): PostgreSQL's sentence for a value
     * too long names only the column's type.
     */
    @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class,
            JDBCException.class})
    public ProblemDetail handleDatabaseRefusal(Exception ex) throws Exception {
        SQLException tooLong = sqlStateIn(ex, VALUE_TOO_LONG);
        if (tooLong != null) {
            // PostgreSQL's sentence names the type, not the column: the wrapper's message, which holds the statement,
            // is logged with it, so the log still says which table the value was too long for
            log.info("Mapping a value too long for its column to 400 Bad Request: {} ({})", tooLong.getMessage(),
                    ex.getMessage());
            Matcher limit = VARCHAR_LIMIT.matcher(String.valueOf(tooLong.getMessage()));
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, limit.find()
                    ? "A value is longer than its field allows: at most " + limit.group(1) + " characters"
                    : "A value is longer than its field allows");
        }
        SQLException deadlock = sqlStateIn(ex, DEADLOCK);
        if (deadlock != null) {
            log.warn("Mapping the loser of a database deadlock to 409 Conflict: {} ({})", deadlock.getMessage(),
                    ex.getMessage());
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "This request crossed another one at the database and was stopped; try again");
        }
        throw ex;
    }

    /** SQLState of a value longer than its column (string data, right truncation) */
    static final String VALUE_TOO_LONG = "22001";

    /** SQLState of the transaction the database rolled back to break a deadlock */
    static final String DEADLOCK = "40P01";

    /** The column's limit in PostgreSQL's sentence */
    private static final Pattern VARCHAR_LIMIT = Pattern.compile("character varying\\((\\d+)\\)");

    /**
     * The first {@link SQLException} of the given SQLState in the cause chain, each one's next exceptions included
     * (a batch failure carries the statement's own failure there), or null.
     */
    static SQLException sqlStateIn(Throwable ex, String sqlState) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = ex; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException first) {
                int hops = 0;
                for (SQLException s = first; s != null && hops < 100; s = s.getNextException(), hops++) {
                    if (sqlState.equals(s.getSQLState())) {
                        return s;
                    }
                }
            }
        }
        return null;
    }
}
