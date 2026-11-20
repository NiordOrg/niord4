/*
 * Copyright 2016 Danish Maritime Authority.
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

package org.niord.web;

import org.niord.core.message.MessageLuceneIndex;
import org.niord.core.message.MessagePostgresIndex;
import org.niord.core.user.Roles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;

/**
 * An extremely simple REST endpoint that can be used to monitor the basic dead-or-alive state of the Niord service.
 * The probes are anonymous; the state of the message search indexes is for system administrators.
 */
@RestController
@RequestMapping("/rest/monitor")
@Transactional
@PermitAll
public class MonitorRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MonitorRestService.class);

    @Inject
    protected EntityManager em;

    @Inject
    MessageLuceneIndex messageLuceneIndex;

    @Inject
    MessagePostgresIndex messagePostgresIndex;

    /** Can be used to see if Niord is running at all **/
    @GetMapping(value = "/ping", produces = "text/plain")
    public String ping() {
        return "pong";
    }

    /** Verifies that Niord is running and that we have a valid DB connection **/
    @GetMapping(value = "/db", produces = "text/plain")
    public String db() {

        try {
            // Just check that the database exists
            em.createNativeQuery("select 1")
                .getSingleResult();

            return "success";

        } catch (Exception ex) {
            // Log the full exception server-side; do NOT echo it to the (anonymous) caller — the raw
            // JDBC message leaks the connection URL/host/user/SQLSTATE (SECAUD-11).
            log.error("Error accessing database", ex);
            throw new NiordWebException("Error accessing database", 500);
        }
    }

    /**
     * Whether the two message search indexes hold every message. After a start, and after a re-index, the backend
     * fills both again, and until then a free-text search answers from part of the messages; a script that prepares a
     * database polls this until {@code complete} is true. It reads two flags held in memory: no database, no side
     * effect, cheap to call every few seconds.
     *
     * @return one flag per index and whether both are complete
     */
    @GetMapping(value = "/message-index", produces = "application/json")
    @RolesAllowed(Roles.SYSADMIN)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MessageIndexState messageIndex() {
        boolean lucene = messageLuceneIndex.allMessagesIndexed();
        boolean postgres = messagePostgresIndex.allMessagesIndexed();
        return new MessageIndexState(lucene, postgres, lucene && postgres);
    }

    /**
     * The answer of {@code GET /rest/monitor/message-index}
     *
     * @param lucene   whether the Lucene index holds every message
     * @param postgres whether the PostgreSQL search table holds every message
     * @param complete whether both do
     */
    public record MessageIndexState(boolean lucene, boolean postgres, boolean complete) {
    }
}
