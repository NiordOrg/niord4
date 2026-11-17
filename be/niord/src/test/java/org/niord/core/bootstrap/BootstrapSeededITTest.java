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
package org.niord.core.bootstrap;

import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A seeded database is never bootstrapped (docs/code-data-config-plan.md §7.2, D4): the IT suite's seeded database
 * (the fixture: the base product's bootstrap and the sample, dumped without the marker) held domains, areas,
 * messages and the rest at its first start, so the marker reads {@code existing}, and the runner and the command
 * refuse it, leaving every row as it is. It stands for every running deployment. Forced back to {@code pending} by
 * hand, it is still refused: the claim also checks that the database holds no data (D4 as amended on 2026-10-24).
 */
class BootstrapSeededITTest extends ITTest {

    @Autowired
    BootstrapMarker marker;

    @Autowired
    BootstrapRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\"", Integer.class);
    }

    @Test
    void theSeededDatabaseReadsExisting() {
        assertEquals(BootstrapMarker.EXISTING, marker.read().orElseThrow().state());

        // Written once: a later start does not decide again
        marker.init();
        assertEquals(BootstrapMarker.EXISTING, marker.read().orElseThrow().state());

        given().auth().oauth2(accessToken())
                .when().get("/rest/bootstrap")
                .then().statusCode(200).body("state", is(BootstrapMarker.EXISTING));
    }

    @Test
    void theRunnerAndTheCommandRefuseIt() {
        int domains = count("Domain");
        int resources = count("ScriptResource");
        byte[] bootstrap = BootstrapArchiveTest.zipFolder(BootstrapArchiveTest.BOOTSTRAP);

        BootstrapRefusedException e = assertThrows(BootstrapRefusedException.class,
                () -> runner.run(List.of(BootstrapArchiveTest.read("niord-bootstrap.zip", bootstrap))));
        assertEquals(BootstrapRefusedException.STATE, e.getStatus());
        assertTrue(e.getMessage().contains("'existing'"), e.getMessage());

        given().auth().oauth2(accessToken())
                .multiPart("archive", "niord-bootstrap.zip", bootstrap, "application/zip")
                .when().post("/rest/bootstrap")
                .then().statusCode(409).body("detail", containsString("'existing'"));

        assertEquals(BootstrapMarker.EXISTING, marker.read().orElseThrow().state());
        assertEquals(domains, count("Domain"), "no row is written");
        assertEquals(resources, count("ScriptResource"), "no row is written");
    }

    @Test
    void theSeededDatabaseForcedToPendingIsRefusedNamingATable() {
        int domains = count("Domain");
        int resources = count("ScriptResource");
        byte[] bootstrap = BootstrapArchiveTest.zipFolder(BootstrapArchiveTest.BOOTSTRAP);
        jdbc.update("UPDATE public.\"BootstrapState\" SET state = 'pending' WHERE id = 1");
        try {
            BootstrapRefusedException e = assertThrows(BootstrapRefusedException.class,
                    () -> runner.claim(List.of(BootstrapArchiveTest.read("niord-bootstrap.zip", bootstrap))));
            assertEquals(BootstrapRefusedException.DATA, e.getStatus());
            assertTrue(e.getMessage().matches("(?s).*a row in \"[A-Za-z_]+\".*"), e.getMessage());
            assertEquals(BootstrapMarker.PENDING, marker.read().orElseThrow().state(), "the claim wrote nothing");
            assertEquals(domains, count("Domain"), "no row is written");
            assertEquals(resources, count("ScriptResource"), "no row is written");
        } finally {
            jdbc.update("UPDATE public.\"BootstrapState\" SET state = 'existing' WHERE id = 1");
        }
        assertEquals(BootstrapMarker.EXISTING, marker.read().orElseThrow().state());
    }
}
