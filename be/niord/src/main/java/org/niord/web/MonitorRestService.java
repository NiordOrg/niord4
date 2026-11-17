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

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;

/**
 * An extremely simple REST endpoint that can be used to monitor the basic dead-or-alive state of the Niord service.
 */
@RestController
@RequestMapping("/rest/monitor")
@Transactional
@PermitAll
public class MonitorRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MonitorRestService.class);

    @Inject
    protected EntityManager em;

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

}
