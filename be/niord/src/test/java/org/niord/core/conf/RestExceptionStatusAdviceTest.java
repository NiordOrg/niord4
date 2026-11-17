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
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;
import jakarta.persistence.RollbackException;
import jakarta.servlet.ServletException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.Test;
import org.niord.core.publication.series.criteria.CriteriaParseException;
import org.niord.web.publication.PublicationExceptionAdvice;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.BatchUpdateException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * What {@link RestExceptionStatusAdvice} answers for a failure the database reports, through Spring's own handler
 * selection (a stand-alone {@link MockMvc} with the advice), so the test also shows which handler a failure reaches.
 *
 * <p>A value longer than its column (SQLState 22001, ERR7) answers 400 with the column's limit, in either of the two
 * wrappers seen over HTTP: Spring's {@code DataIntegrityViolationException} (a flush at commit, translated by the
 * transaction manager) and Hibernate's own {@code DataException} (a flush inside the request); and in a wrapper around
 * them, which Spring hands the handler through the cause. The loser of a deadlock (SQLState 40P01) answers 409 with
 * "try again". Every other database failure is left to the handling it had before: it is not
 * resolved here and reaches the servlet container, which answers 500, as it did.
 */
class RestExceptionStatusAdviceTest {

    /** The sentence PostgreSQL gives a value longer than a varchar(255) column. */
    private static final String TOO_LONG_255 = "ERROR: value too long for type character varying(255)";

    /**
     * A controller that throws whatever the test hands it. It is an inner class, not a static one: the scans of every
     * controller under {@code org.niord} take only classes that stand on their own, so the condition-blind guards
     * ({@code RestControllerSecurityAnnotationTest}, {@code EndpointTableCompletenessTest}), which read the test
     * classes too, do not take it for a handler of the application, and no integration test's component scan
     * registers it. The stand-alone {@link MockMvc} is handed the instance and maps it all the same.
     */
    @RestController
    class Thrower {
        RuntimeException next;

        @GetMapping("/fail")
        public String fail() {
            throw next;
        }
    }

    private final Thrower thrower = new Thrower();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(thrower)
            .setControllerAdvice(new RestExceptionStatusAdvice())
            .build();

    private MvcResult answer(RuntimeException e) throws Exception {
        thrower.next = e;
        return mvc.perform(get("/fail")).andReturn();
    }

    /** The failure is not resolved by the advice: it leaves the dispatcher as it came, for the container's 500. */
    private void assertLeftAlone(RuntimeException e) {
        thrower.next = e;
        Exception thrown = assertThrows(Exception.class, () -> mvc.perform(get("/fail")).andReturn(),
                e + " was answered by the advice");
        Throwable left = thrown instanceof ServletException && thrown.getCause() != null ? thrown.getCause() : thrown;
        assertSame(e, left, "the original failure leaves the dispatcher");
    }

    private static SQLException sql(String message, String state) {
        return new SQLException(message, state);
    }

    // ------------------------------------------------------------------------------------------- //
    // 22001: a value longer than its column (ERR7)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aValueTooLongAtCommitAnswers400WithTheLimit() throws Exception {
        MvcResult r = answer(new DataIntegrityViolationException("could not execute statement",
                new DataException("could not execute statement", sql(TOO_LONG_255, "22001"))));
        assertEquals(400, r.getResponse().getStatus());
        String body = r.getResponse().getContentAsString();
        assertTrue(body.contains("at most 255 characters"), body);
    }

    @Test
    void aValueTooLongAtAFlushInsideAServiceAnswers400() throws Exception {
        MvcResult r = answer(new DataException("could not execute statement",
                sql("ERROR: value too long for type character varying(1000)", "22001")));
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("at most 1000 characters"));
    }

    /** A wrapper no handler names reaches the handler through its cause, and the whole chain is read. */
    @Test
    void aValueTooLongInsideACommitFailureAnswers400() throws Exception {
        MvcResult r = answer(new TransactionSystemException("Could not commit JPA transaction",
                new RollbackException("Error while committing the transaction",
                        new DataException("could not execute statement",
                                sql("ERROR: value too long for type character varying(2000)", "22001")))));
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("at most 2000 characters"));
    }

    @Test
    void aValueTooLongInABatchIsFoundBehindTheBatchFailure() throws Exception {
        BatchUpdateException batch = new BatchUpdateException(
                "Batch entry 0 insert into \"AreaDesc\" values ('secret words') was aborted", null, new int[0]);
        batch.setNextException(sql(TOO_LONG_255, "22001"));
        MvcResult r = answer(new DataIntegrityViolationException("could not execute batch",
                new DataException("could not execute batch", batch)));
        assertEquals(400, r.getResponse().getStatus());
        String body = r.getResponse().getContentAsString();
        assertTrue(body.contains("at most 255 characters"), body);
        assertFalse(body.contains("secret"), "the statement and its values stay in the log: " + body);
        assertFalse(body.contains("AreaDesc"), "the statement and its values stay in the log: " + body);
    }

    /**
     * The log line keeps what names the field (the review's finding R7): PostgreSQL's sentence names only the type, so
     * the line holds the wrapper's message too, which holds the statement and its table. The answer does not.
     */
    @Test
    void aValueTooLongIsLoggedWithTheStatementThatNamesItsTable() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RestExceptionStatusAdvice.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MvcResult r = answer(new DataException(
                    "could not execute statement [ERROR: value too long for type character varying(10)]"
                            + " [insert into \"AreaDesc\" (\"name\") values (?)]",
                    sql("ERROR: value too long for type character varying(10)", "22001"),
                    "insert into \"AreaDesc\" (\"name\") values (?)"));
            assertEquals(400, r.getResponse().getStatus());
            assertFalse(r.getResponse().getContentAsString().contains("AreaDesc"),
                    "the statement stays out of the answer");
            assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("AreaDesc")),
                    "the log names the statement's table: " + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aValueTooLongWithoutALimitInTheSentenceStillAnswers400() throws Exception {
        MvcResult r = answer(new DataIntegrityViolationException("could not execute statement",
                new DataException("could not execute statement", sql("value too long", "22001"))));
        assertEquals(400, r.getResponse().getStatus());
        String body = r.getResponse().getContentAsString();
        assertTrue(body.contains("longer than its field allows"), body);
        assertFalse(body.contains("value too long"), "the database's sentence stays in the log: " + body);
    }

    // ------------------------------------------------------------------------------------------- //
    // 40P01: the loser of a deadlock
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theLoserOfADeadlockAnswers409TryAgain() throws Exception {
        SQLException deadlock = sql("ERROR: deadlock detected\n  Detail: Process 1 waits for ShareLock", "40P01");
        for (RuntimeException e : new RuntimeException[] {
                // a flush at commit, translated by the transaction manager
                new CannotAcquireLockException("could not execute statement",
                        new LockAcquisitionException("could not execute statement", deadlock)),
                // a flush inside a service: Hibernate's own conversion, bare and in the jakarta wrapper (which
                // reaches the handler through its cause)
                new LockAcquisitionException("could not execute statement", deadlock),
                new PessimisticLockException("could not execute statement",
                        new LockAcquisitionException("could not execute statement", deadlock)),
                // a JdbcTemplate statement (the message number's row lock), translated by its error codes
                new PessimisticLockingFailureException("PreparedStatementCallback", deadlock)}) {
            MvcResult r = answer(e);
            assertEquals(409, r.getResponse().getStatus(), e.getClass().getSimpleName());
            String body = r.getResponse().getContentAsString();
            assertTrue(body.contains("try again"), body);
            assertFalse(body.contains("Process 1"), "the database's sentence stays in the log: " + body);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Everything else keeps its present answer
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anyOtherDatabaseFailureIsLeftToTheContainer() {
        // A unique key (CONTACT3) and a foreign key (PROM8): their own entries, their present 500
        assertLeftAlone(new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("duplicate key",
                        sql("ERROR: duplicate key value violates unique constraint", "23505"), "uk")));
        assertLeftAlone(new DuplicateKeyException("duplicate key", sql("duplicate key", "23505")));
        assertLeftAlone(new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("foreign key",
                        sql("ERROR: update or delete violates foreign key constraint", "23503"), "fk")));
        // A lock that was not free (NOWAIT, 55P03) is not a deadlock
        assertLeftAlone(new CannotAcquireLockException("could not obtain lock",
                new LockAcquisitionException("could not obtain lock", sql("ERROR: could not obtain lock", "55P03"))));
        // Persistence failures with no SQLState at all
        assertLeftAlone(new PersistenceException("something else"));
        assertLeftAlone(new NoResultException("No result"));
        assertLeftAlone(new TransactionSystemException("Could not commit JPA transaction",
                new RollbackException("Transaction marked as rollbackOnly")));
    }

    @Test
    void theOlderMappingsKeepTheirHandlers() throws Exception {
        assertEquals(409, answer(new ObjectOptimisticLockingFailureException("Area", 1)).getResponse().getStatus());
        assertEquals(404, answer(new EntityNotFoundException("gone")).getResponse().getStatus());
        assertEquals(400, answer(new IllegalArgumentException("bad")).getResponse().getStatus());
        assertEquals(409, answer(new IllegalStateException("not now")).getResponse().getStatus());
    }

    /**
     * Hibernate wraps whatever an {@code AttributeConverter} throws in a plain {@link PersistenceException}. The
     * database handler must not take that wrapper (it would throw it on, and Spring would not offer the cause to any
     * handler again): the cause keeps the answer its own handler gives.
     */
    @Test
    void aPersistenceExceptionAroundADomainFailureKeepsTheCausesAnswer() throws Exception {
        String converter = "Error attempting to apply AttributeConverter";
        assertEquals(400, answer(new PersistenceException(converter,
                new IllegalArgumentException("bad value"))).getResponse().getStatus());
        assertEquals(409, answer(new PersistenceException(converter,
                new IllegalStateException("not now"))).getResponse().getStatus());
        assertEquals(404, answer(new PersistenceException(converter,
                new EntityNotFoundException("gone"))).getResponse().getStatus());
    }

    /**
     * A publication refusal wrapped in a {@link PersistenceException} (stored criteria the converter cannot read) keeps
     * the publication advice's coded refusal, with this advice registered first, as in the application.
     */
    @Test
    void aPersistenceExceptionAroundAPublicationRefusalKeepsTheCodedRefusal() throws Exception {
        MockMvc both = MockMvcBuilders.standaloneSetup(thrower)
                .setControllerAdvice(new RestExceptionStatusAdvice(), new PublicationExceptionAdvice())
                .build();
        thrower.next = new PersistenceException("Error attempting to apply AttributeConverter",
                new CriteriaParseException("criteria cannot be read", null));
        MvcResult r = both.perform(get("/fail")).andReturn();
        assertEquals(400, r.getResponse().getStatus());
        String body = r.getResponse().getContentAsString();
        assertTrue(body.contains("\"code\":\"CRITERIA_INVALID\""), body);
    }
}
