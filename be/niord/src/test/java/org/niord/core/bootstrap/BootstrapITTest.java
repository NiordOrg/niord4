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

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.niord.core.category.CategoryService;
import org.niord.core.category.TemplateExecutionService;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.report.FmReportService;
import org.niord.test.ApplicationDatabaseUser;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.NiordTestApplication;
import org.niord.test.NiordTestContainers;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bootstrap on a fresh database (docs/code-data-config-plan.md §7.2, D4 as amended on 2026-10-24): a bootstrap
 * runs once, on a new, empty database. The marker reads {@code pending} once; a database holding a domain, an area or
 * a message reads {@code existing}. The claim, in one locked transaction, refuses a marker that does not read
 * {@code pending} and a {@code pending} one over a database that holds data, naming the first table with a row, while
 * start-up's own rows (the settings, the default texts, the ledger, the users) do not count. An {@code in-progress}
 * marker is refused live ("wait") and stale ("broke off", recreate), with one archive and with two; a failed run is
 * refused for good, whatever the number of archives, in-process and through the command, and the remedy is a
 * database emptied again ({@link #emptyTheDatabase}, this class's stand-in for one recreated) and the same bootstrap;
 * {@code applied} is refused; an archive with a script the script check refuses is refused before the marker is
 * claimed; and what the base product's bootstrap puts into the database, every script-resource history row naming
 * the sysadmin who ran it. Start-up itself writes no domain, category, report, parameter type or script resource.
 * A country's archive after the base product's that restates the master domain with active false only leaves it
 * inactive within the first bootstrap, and its own domain stays active.
 *
 * <p>Like {@code LiquibaseBootstrapITTest} it does not extend {@code ITTest}, whose seeded database reads
 * {@code existing} ({@link BootstrapSeededITTest}): it starts the application on a fresh, un-seeded PostGIS
 * container with the bare master changelog. The methods run in order on that one database, the last ones on the
 * bootstrapped state the earlier ones leave.
 */
@SpringBootTest(classes = NiordTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BootstrapITTest {

    private static final PostgreSQLContainer<?> FRESH_POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("imresamu/postgis:18-3.6")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("niord")
                    .withUsername("niord")
                    .withPassword("niord");

    private static final String BOOTSTRAP = "/rest/bootstrap";

    static {
        // As LiquibaseBootstrapITTest: NIORD_HOME and twitter4j's restBaseURL, which ITTest's static block sets
        // before any context is built (run here by initialising that class), and niord.home for the fresh database
        try {
            Class.forName(ITTest.class.getName(), true, BootstrapITTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
        if (System.getProperty("niord.home") == null) {
            String home = System.getenv("NIORD_HOME") != null
                    ? System.getenv("NIORD_HOME") : System.getProperty("NIORD_HOME");
            System.setProperty("niord.home", home);
        }
    }

    private static PostgreSQLContainer<?> freshPostgres() {
        if (!FRESH_POSTGRES.isRunning()) {
            FRESH_POSTGRES.start();
        }
        return FRESH_POSTGRES;
    }

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(NiordTestContainers.dockerAvailable(),
                "Docker is not available - skipping Niord integration tests");
    }

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> freshPostgres().getJdbcUrl());
        // As LiquibaseBootstrapITTest: the application's own database user, not the image's superuser, so the
        // bootstrap of a new deployment runs as the user it runs as there
        registry.add("spring.datasource.username", () -> ApplicationDatabaseUser.of(freshPostgres()).name());
        registry.add("spring.datasource.password", () -> ApplicationDatabaseUser.of(freshPostgres()).password());
        registry.add("spring.jpa.properties.jakarta.persistence.validation.mode", () -> "none");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", NiordTestContainers::keycloakRealmUrl);
        registry.add("spring.mail.host", NiordTestContainers::smtpHost);
        registry.add("spring.mail.port", () -> NiordTestContainers.smtpPort());
        registry.add("niord.smtp.host", NiordTestContainers::smtpHost);
        registry.add("niord.smtp.port", () -> NiordTestContainers.smtpPort());
        // As ITTest: no status sweep runs during the tests
        registry.add("niord.schedule.auto-publish-cron", () -> "-");
        registry.add("niord.schedule.auto-expire-cron", () -> "-");
        registry.add("niord.schedule.firing-exercise-cron", () -> "-");
        registry.add("niord.mailinglist.status-change-sweep-cron", () -> "-");
    }

    @LocalServerPort
    int port;

    @Autowired
    BootstrapMarker marker;

    @Autowired
    BootstrapRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @PersistenceContext
    EntityManager em;

    @Autowired
    TemplateExecutionService templateExecutionService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    FmReportService fmReportService;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.basePath = "";
    }

    private String state() {
        return marker.read().map(BootstrapMarker.State::state).orElse(null);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\"", Integer.class);
    }

    private static BootstrapArchive archive(String name, byte[] zip) {
        return BootstrapArchiveTest.read(name, zip);
    }

    private static byte[] baseBootstrap() {
        return BootstrapArchiveTest.zipFolder(BootstrapArchiveTest.BOOTSTRAP);
    }

    // ------------------------------------------------------------------------------------------- //

    @Test
    @Order(1)
    void theFirstStartWritesPendingOnceAndNoData() {
        assertEquals(BootstrapMarker.PENDING, state(), "a database with no domain, message or area reads pending");
        for (String table : List.of("Domain", "Category", "FmReport", "ParamType", "ScriptResource")) {
            assertEquals(0, count(table), "start-up writes no \"" + table + "\" row");
        }

        // Written once: a later start leaves the row as it is
        String updated = String.valueOf(marker.read().orElseThrow().updated());
        marker.init();
        assertEquals(BootstrapMarker.PENDING, state());
        assertEquals(updated, String.valueOf(marker.read().orElseThrow().updated()));
    }

    @Test
    @Order(2)
    void aDatabaseHoldingADomainAnAreaOrAMessageReadsExisting() {
        Map<String, String> rows = Map.of(
                "Domain", "INSERT INTO public.\"Domain\" (id, active, \"domainId\", name) "
                        + "VALUES (-1, true, 'it-domain', 'IT')",
                "Area", "INSERT INTO public.\"Area\" (id, active) VALUES (-1, true)",
                "Message", "INSERT INTO public.\"Message\" (id, \"autoTitle\", \"hasGeometry\", \"mainType\", "
                        + "\"repoPath\", revision, status, type, uid) "
                        + "VALUES (-1, false, false, 'NW', 'it/bootstrap', 0, 'DRAFT', 'LOCAL_WARNING', 'it-bootstrap')");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        rows.forEach((table, insert) -> tx.executeWithoutResult(status -> {
            // As at the first start of a version with the marker, on a database that holds the row; rolled back
            em.createNativeQuery("DELETE FROM public.\"BootstrapState\"").executeUpdate();
            em.createNativeQuery(insert).executeUpdate();
            marker.init();
            assertEquals(BootstrapMarker.EXISTING, state(), "a database holding a \"" + table + "\" row");
            status.setRollbackOnly();
        }));
        assertEquals(BootstrapMarker.PENDING, state());
    }

    /**
     * The claim commits at once, in a transaction of its own, also when the caller holds one that is rolled back
     */
    @Test
    @Order(2)
    void theClaimCommitsAtOnceInsideACallersTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            tx.executeWithoutResult(status -> {
                assertTrue(marker.claim("it-claim").claimed());
                status.setRollbackOnly();
            });
            assertEquals(BootstrapMarker.IN_PROGRESS, state(), "the claim committed before the caller rolled back");
            assertEquals("it-claim", marker.read().orElseThrow().hash());
        } finally {
            jdbc.update("UPDATE public.\"BootstrapState\" SET state = 'pending', hash = NULL, updated = now() "
                    + "WHERE id = 1");
        }
        assertEquals(BootstrapMarker.PENDING, state());
    }

    @Test
    @Order(3)
    void theCommandAnswersTheMarkerToASysadminOnlyAndRefusesABrokenArchive() {
        given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .when().get(BOOTSTRAP)
                .then().statusCode(200).contentType(ContentType.JSON).body("state", is(BootstrapMarker.PENDING));
        given().when().get(BOOTSTRAP).then().statusCode(401);
        given().auth().oauth2(KeycloakTestTokens.adminAccessToken()).when().get(BOOTSTRAP).then().statusCode(403);
        given().auth().oauth2(KeycloakTestTokens.adminAccessToken())
                .multiPart("archive", "niord-bootstrap.zip", baseBootstrap(), "application/zip")
                .when().post(BOOTSTRAP).then().statusCode(403);

        // An archive rule is checked before the marker is claimed
        given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .multiPart("archive", "broken.zip", "not a zip".getBytes(), "application/zip")
                .when().post(BOOTSTRAP)
                .then().statusCode(400).body("detail", containsString("'broken.zip'"));
        given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .contentType("multipart/form-data").multiPart("other", "x")
                .when().post(BOOTSTRAP).then().statusCode(400);

        // So is the script check of every template and script the archive carries
        byte[] badScript = BootstrapArchiveTest.zip(BootstrapArchiveTest.withTemplate("templates/tmpl/bad.js",
                "var r = Java.type('java.lang.Runtime');"));
        given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .multiPart("archive", "bad-script.zip", badScript, "application/zip")
                .when().post(BOOTSTRAP)
                .then().statusCode(400)
                .body("detail", containsString("'templates/tmpl/bad.js'"))
                .body("detail", containsString("Java.type"));
        assertEquals(BootstrapMarker.PENDING, state());
        assertEquals(0, count("ScriptResource"), "nothing was written");

        // The administration app opens on a database that is not bootstrapped yet: no built-in report is there,
        // and the detail reports are the ones that exist
        given().when().get("/rest/message-reports/detail-reports")
                .then().statusCode(200).body("size()", is(0));
        given().when().get("/rest/settings/web").then().statusCode(200);
    }

    /** Polls the marker until it no longer reads in-progress, for at most three minutes */
    private BootstrapMarker.State awaitTheEndOfTheRun() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
        while (BootstrapMarker.IN_PROGRESS.equals(state()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        return marker.read().orElseThrow();
    }

    @Test
    @Order(4)
    void inProgressIsRefused() {
        // A run under way: committed, as the runner's claim commits, and touched just now, as its heartbeat does
        jdbc.update("UPDATE public.\"BootstrapState\" SET state = 'in-progress', updated = now() WHERE id = 1");
        try {
            BootstrapRefusedException e = assertThrows(BootstrapRefusedException.class,
                    () -> runner.claim(List.of(archive("niord-bootstrap.zip", baseBootstrap()))));
            assertEquals(BootstrapRefusedException.STATE, e.getStatus());
            assertTrue(e.getMessage().contains("'in-progress'") && e.getMessage().contains("wait"), e.getMessage());
            given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                    .multiPart("archive", "niord-bootstrap.zip", baseBootstrap(), "application/zip")
                    .when().post(BOOTSTRAP)
                    .then().statusCode(409).body("detail", containsString("'in-progress'"));
            assertEquals(0, count("Domain"));
        } finally {
            jdbc.update("UPDATE public.\"BootstrapState\" SET state = 'pending' WHERE id = 1");
        }
    }

    private static byte[] minimal() {
        return BootstrapArchiveTest.zipFolder(BootstrapArchiveTest.FIXTURES.resolve("minimal"));
    }

    /** A row committed by hand in a table, and the statement that deletes it again */
    private record Row(String table, List<String> inserts, List<String> deletes) {
    }

    /**
     * A pending marker over a database that holds data is refused, naming the first table that holds a row, in-process
     * and through the command, and the marker is left as it was; the rows a first start writes by itself (a default
     * text, the settings, the users mirrored from Keycloak) do not count. Committed with
     * autocommit, as the claim reads in a transaction of its own, and deleted again.
     */
    @Test
    @Order(4)
    void aPendingMarkerOverDataIsRefusedNamingTheTable() {
        List<String> tables = marker.dataTables();
        assertTrue(tables.indexOf("BatchData") >= 0 && tables.indexOf("BatchData") < tables.indexOf("Sequence"),
                "BatchData is probed before Sequence: " + tables);
        for (String notData : BootstrapMarker.NOT_DATA) {
            assertFalse(tables.contains(notData), notData + " is not probed");
        }
        assertFalse(tables.contains("spatial_ref_sys"), "an extension's table is not probed");

        List<Row> rows = List.of(
                new Row("ScriptResource", List.of("INSERT INTO public.\"ScriptResource\" (id, path, type, content, "
                        + "created, updated, version) VALUES (-1, 'templates/it/data.ftl', 'FM', '', now(), now(), 0)"),
                        List.of("DELETE FROM public.\"ScriptResource\" WHERE id = -1")),
                new Row("Domain", List.of("INSERT INTO public.\"Domain\" (id, active, \"domainId\", name) "
                        + "VALUES (-1, true, 'it-domain', 'IT')"),
                        List.of("DELETE FROM public.\"Domain\" WHERE id = -1")),
                new Row("Message", List.of("INSERT INTO public.\"Message\" (id, \"autoTitle\", \"hasGeometry\", "
                        + "\"mainType\", \"repoPath\", revision, status, type, uid) VALUES (-1, false, false, 'NW', "
                        + "'it/bootstrap', 0, 'DRAFT', 'LOCAL_WARNING', 'it-bootstrap')"),
                        List.of("DELETE FROM public.\"Message\" WHERE id = -1")),
                new Row("BatchData", List.of("INSERT INTO public.\"BatchData\" (id, created, \"instanceId\", "
                        + "\"jobName\", \"jobNo\") VALUES (-1, now(), -1, 'it-job', 1)"),
                        List.of("DELETE FROM public.\"BatchData\" WHERE id = -1")),
                // A key of a dictionary that is not a default text's: its dictionary row is found first
                new Row("Dictionary", List.of("INSERT INTO public.\"Dictionary\" (id, name) VALUES (-1, 'aton')",
                        "INSERT INTO public.\"DictionaryEntry\" (id, dict_key, dictionary_id) "
                                + "VALUES (-1, 'it-key', -1)"),
                        List.of("DELETE FROM public.\"DictionaryEntry\" WHERE id = -1",
                                "DELETE FROM public.\"Dictionary\" WHERE id = -1")));
        byte[] base = baseBootstrap();
        for (Row row : rows) {
            row.inserts().forEach(jdbc::update);
            try {
                var before = marker.read().orElseThrow();
                assertEquals(Optional.of(row.table()), marker.firstDataTable());
                BootstrapRefusedException e = assertThrows(BootstrapRefusedException.class,
                        () -> runner.claim(List.of(archive("niord-bootstrap.zip", base))));
                assertEquals(BootstrapRefusedException.DATA, e.getStatus());
                assertTrue(e.getMessage().contains("a row in \"" + row.table() + "\""), e.getMessage());
                given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                        .multiPart("archive", "niord-bootstrap.zip", base, "application/zip")
                        .when().post(BOOTSTRAP)
                        .then().statusCode(409)
                        .body("detail", containsString("a row in \"" + row.table() + "\""))
                        .body("detail", containsString("recreate the database empty"));
                assertEquals(before, marker.read().orElseThrow(), "the marker is left as it was");
            } finally {
                row.deletes().forEach(jdbc::update);
            }
        }

        // A default text's key does not count, nor do the settings, the users and a queued alert mail with its
        // recipient (a scheduled alert may queue one before the bootstrap is posted); a domain beside them still does
        jdbc.update("INSERT INTO public.\"DictionaryEntry\" (id, dict_key, dictionary_id) "
                + "SELECT -2, 'it-web-key', id FROM public.\"Dictionary\" WHERE name = 'web'");
        jdbc.update("INSERT INTO public.\"ScheduledMail\" (id, attempts, contents, created, status, subject) "
                + "VALUES (-1, 0, '\\x00'::bytea, now(), 'PENDING', 'IT alert')");
        jdbc.update("INSERT INTO public.\"ScheduledMailRecipient\" (id, address, \"recipientType\", mail_id) "
                + "VALUES (-1, 'it@example.org', 'TO', -1)");
        try {
            jdbc.update("INSERT INTO public.\"Domain\" (id, active, \"domainId\", name) VALUES (-1, true, 'it-domain', "
                    + "'IT')");
            try {
                assertEquals(Optional.of("Domain"), marker.firstDataTable(), "a domain beside the queued mail");
                BootstrapRefusedException e = assertThrows(BootstrapRefusedException.class,
                        () -> runner.claim(List.of(archive("niord-bootstrap.zip", base))));
                assertTrue(e.getMessage().contains("a row in \"Domain\""), e.getMessage());
            } finally {
                jdbc.update("DELETE FROM public.\"Domain\" WHERE id = -1");
            }
            assertTrue(count("Setting") > 0, "start-up wrote the settings");
            assertEquals(1, count("ScheduledMail"), "the queued mail");
            assertEquals(Optional.empty(), marker.firstDataTable());
            assertTrue(marker.claim("it-empty").claimed(), "a database holding only start-up's rows is claimed");
        } finally {
            jdbc.update("DELETE FROM public.\"ScheduledMailRecipient\" WHERE id = -1");
            jdbc.update("DELETE FROM public.\"ScheduledMail\" WHERE id = -1");
            jdbc.update("DELETE FROM public.\"DictionaryEntry\" WHERE id = -2");
            jdbc.update("UPDATE public.\"BootstrapState\" SET state = 'pending', hash = NULL, error = NULL "
                    + "WHERE id = 1");
        }
        assertEquals(BootstrapMarker.PENDING, state());
    }

    /**
     * An in-progress marker is never claimed: one with no sign of life for five minutes is a run that broke off, and
     * is refused with one archive and with two, the remedy named and the marker left as it was; a live one is refused
     * with "wait". The heartbeat touches the run's own marker only, and the note says why it is still in-progress.
     */
    @Test
    @Order(4)
    void anInProgressMarkerIsRefusedLiveOrStale() {
        try {
            jdbc.update("UPDATE public.\"BootstrapState\" SET state = 'in-progress', hash = 'broken-off', "
                    + "updated = now() - interval '6 minutes' WHERE id = 1");
            var untouched = marker.read().orElseThrow();
            byte[] base = baseBootstrap();
            for (List<BootstrapArchive> archives : List.of(List.of(archive("niord-bootstrap.zip", base)),
                    List.of(archive("niord-bootstrap.zip", base), archive("minimal.zip", minimal())))) {
                BootstrapRefusedException refused = assertThrows(BootstrapRefusedException.class,
                        () -> runner.claim(archives));
                assertEquals(BootstrapRefusedException.STATE, refused.getStatus());
                assertTrue(refused.getMessage().contains("'in-progress' with no sign of life")
                        && refused.getMessage().contains("broke off")
                        && refused.getMessage().contains("recreate the database empty"), refused.getMessage());
                assertEquals(untouched, marker.read().orElseThrow(), "the marker is left as it was");
            }
            given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                    .multiPart("archive", "niord-bootstrap.zip", base, "application/zip")
                    .when().post(BOOTSTRAP)
                    .then().statusCode(409).body("detail", containsString("broke off"));
            assertEquals(untouched, marker.read().orElseThrow(), "the marker is left as it was");
            assertEquals(0, count("Domain"), "a refused claim writes no data");

            // The heartbeat touches the run's own marker, and only that
            jdbc.update("UPDATE public.\"BootstrapState\" SET updated = now() - interval '4 minutes' WHERE id = 1");
            var before = marker.read().orElseThrow().updated();
            marker.heartbeat("another run");
            assertEquals(before, marker.read().orElseThrow().updated(), "another run's heartbeat touches nothing");
            marker.heartbeat("broken-off");
            assertTrue(marker.read().orElseThrow().updated().isAfter(before.plusMinutes(3)),
                    "the heartbeat moves updated on");

            // Touched four minutes ago, the run is live: refused, and told to wait
            jdbc.update("UPDATE public.\"BootstrapState\" SET updated = now() - interval '4 minutes' WHERE id = 1");
            BootstrapRefusedException live = assertThrows(BootstrapRefusedException.class,
                    () -> runner.claim(List.of(archive("niord-bootstrap.zip", base))));
            assertTrue(live.getMessage().contains("wait") && !live.getMessage().contains("recreate"),
                    live.getMessage());

            // The note keeps the run in-progress and says why
            marker.note("broken-off", "waiting for execution 1");
            assertEquals("waiting for execution 1", marker.read().orElseThrow().error());
            assertEquals(BootstrapMarker.IN_PROGRESS, state());
        } finally {
            jdbc.update("UPDATE public.\"BootstrapState\" SET state = 'pending', hash = NULL, error = NULL "
                    + "WHERE id = 1");
        }
    }

    /**
     * Empties the database as a recreated one is: every table the claim probes truncated in one
     * statement, apart from the dictionaries, whose rows outside the default texts' dictionaries are deleted
     * (start-up's texts stay, as on a recreated database's first start); the marker back to {@code pending}; the JPA
     * second-level cache evicted. The claim's own definition of empty then holds.
     */
    private void emptyTheDatabase() {
        int users = count("User");
        List<String> dictionaries = List.of("Dictionary", "DictionaryEntry", "DictionaryEntryDesc");
        jdbc.execute("TRUNCATE " + marker.dataTables().stream()
                .filter(table -> !dictionaries.contains(table))
                .map(table -> "public.\"" + table + "\"")
                .collect(Collectors.joining(", ")) + " CASCADE");
        String outside = "(SELECT id FROM public.\"Dictionary\" WHERE name NOT IN ("
                + Arrays.stream(DictionaryService.DEFAULT_BUNDLES).map(name -> "'" + name + "'")
                .collect(Collectors.joining(", ")) + "))";
        jdbc.update("DELETE FROM public.\"DictionaryEntryDesc\" WHERE entity_id IN (SELECT id "
                + "FROM public.\"DictionaryEntry\" WHERE dictionary_id IN " + outside + ")");
        jdbc.update("DELETE FROM public.\"DictionaryEntry\" WHERE dictionary_id IN " + outside);
        jdbc.update("DELETE FROM public.\"Dictionary\" WHERE id IN " + outside);
        jdbc.update("UPDATE public.\"BootstrapState\" SET state = 'pending', hash = NULL, error = NULL, "
                + "updated = now() WHERE id = 1");
        em.getEntityManagerFactory().getCache().evictAll();
        assertEquals(Optional.empty(), marker.firstDataTable(), "the database is empty again");
        assertEquals(users, count("User"), "the users are kept");
    }

    /**
     * A failed run is recorded and refused for good: posted again with one archive or with two, in-process and through
     * the command, it is refused, naming the remedy; forced back to pending by hand it is still refused, since the run
     * left a row in "BatchData". Emptied, as a recreated database is, the database takes a bootstrap again: a failing
     * one through the command, then, emptied once more, the base product's, as the sysadmin, to {@code applied}.
     */
    @Test
    @Order(5)
    void aFailedRunIsRecordedAndRefused() throws Exception {
        byte[] failing = BootstrapArchiveTest.zipFolder(BootstrapArchiveTest.FIXTURES.resolve("failing"));

        BootstrapRunner.Outcome outcome = runner.run(List.of(archive("failing.zip", failing)));

        assertEquals(BootstrapMarker.FAILED, outcome.state());
        BootstrapMarker.State failed = marker.read().orElseThrow();
        assertEquals(BootstrapMarker.FAILED, failed.state());
        assertTrue(failed.error().contains("domain-import") && failed.error().contains("domains.json")
                        && failed.error().contains("failing.zip") && failed.error().contains("FAILED"),
                "the error names the job, its file, the archive and the status: " + failed.error());
        assertEquals(0, count("Domain"));

        // Never run again on the same database, whatever the number of archives
        byte[] base = baseBootstrap();
        for (List<BootstrapArchive> archives : List.of(List.of(archive("niord-bootstrap.zip", base)),
                List.of(archive("niord-bootstrap.zip", base), archive("minimal.zip", minimal())))) {
            BootstrapRefusedException refused = assertThrows(BootstrapRefusedException.class,
                    () -> runner.claim(archives));
            assertEquals(BootstrapRefusedException.STATE, refused.getStatus());
            assertTrue(refused.getMessage().contains("'failed'") && refused.getMessage().contains("recreate it empty"),
                    refused.getMessage());
        }
        given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .multiPart("archive", "niord-bootstrap.zip", base, "application/zip")
                .when().post(BOOTSTRAP)
                .then().statusCode(409)
                .body("detail", containsString("'failed'"))
                .body("detail", containsString("recreate it empty"));
        given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .multiPart("archive", "niord-bootstrap.zip", base, "application/zip")
                .multiPart("archive", "minimal.zip", minimal(), "application/zip")
                .when().post(BOOTSTRAP)
                .then().statusCode(409)
                .body("detail", containsString("'failed'"))
                .body("detail", containsString("recreate it empty"));
        BootstrapMarker.State stillFailed = marker.read().orElseThrow();
        assertEquals(BootstrapMarker.FAILED, stillFailed.state());
        assertEquals(failed.error(), stillFailed.error(), "the marker keeps its error");
        assertEquals(0, count("Domain"));

        // Forced back to pending by hand, the database still holds what the failed run wrote
        jdbc.update("UPDATE public.\"BootstrapState\" SET state = 'pending', error = NULL WHERE id = 1");
        BootstrapRefusedException overData = assertThrows(BootstrapRefusedException.class,
                () -> runner.claim(List.of(archive("niord-bootstrap.zip", base))));
        assertEquals(BootstrapRefusedException.DATA, overData.getStatus());
        assertTrue(overData.getMessage().contains("a row in \"BatchData\""), overData.getMessage());
        given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .multiPart("archive", "niord-bootstrap.zip", base, "application/zip")
                .when().post(BOOTSTRAP)
                .then().statusCode(409).body("detail", containsString("a row in \"BatchData\""));
        assertEquals(BootstrapMarker.PENDING, state(), "a refusal over data leaves the marker as it was");

        // Emptied, the database takes a bootstrap again: through the command, as bootstrap.sh runs it, accepted, then
        // failed in the background
        emptyTheDatabase();
        given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .multiPart("archive", "failing.zip", failing, "application/zip")
                .when().post(BOOTSTRAP)
                .then().statusCode(202).body("state", is(BootstrapMarker.IN_PROGRESS));
        BootstrapMarker.State failedByCommand = awaitTheEndOfTheRun();
        assertEquals(BootstrapMarker.FAILED, failedByCommand.state(), String.valueOf(failedByCommand.error()));
        assertTrue(failedByCommand.error().contains("job domain-import (domains.json in failing.zip)"),
                failedByCommand.error());
        given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .when().get(BOOTSTRAP)
                .then().statusCode(200).body("state", is(BootstrapMarker.FAILED))
                .body("error", containsString("domain-import"));
        assertEquals(0, count("Domain"));

        // Emptied once more: the base product's bootstrap, through the command, as the sysadmin: answered at once,
        // applied in the background
        emptyTheDatabase();
        given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .multiPart("archive", "niord-bootstrap.zip", base, "application/zip")
                .when().post(BOOTSTRAP)
                .then().statusCode(202).body("state", is(BootstrapMarker.IN_PROGRESS));

        BootstrapMarker.State applied = awaitTheEndOfTheRun();
        assertEquals(BootstrapMarker.APPLIED, applied.state(), "the bootstrap is applied: " + applied.error());
        assertEquals(BootstrapArchive.runHash(List.of(archive("niord-bootstrap.zip", base))),
                applied.hash(), "the marker records the run's hash");

        // The batch jobs ran on the sysadmin's security context
        Long bySysadmin = em.createQuery(
                        "select count(b) from BatchData b where b.user.username = 'sysadmin'", Long.class)
                .getSingleResult();
        assertTrue(bySysadmin >= 7, "every job of the run names the sysadmin: " + bySysadmin);
    }

    @Test
    @Order(6)
    void theBaseProductsBootstrapIsInTheDatabase() {
        assertEquals(List.of("niord-client-master"),
                jdbc.queryForList("SELECT \"domainId\" FROM public.\"Domain\"", String.class));
        assertEquals(List.of(true), jdbc.queryForList(
                "SELECT active FROM public.\"Domain\" WHERE \"domainId\" = 'niord-client-master'", Boolean.class),
                "a base-only deployment's only domain is active, so its sysadmin has a session domain and reaches "
                        + "the sysadmin pages");
        assertEquals(80, count("ScriptResource"), "the 80 templates and scripts");
        assertEquals(List.of("import"), jdbc.queryForList(
                "SELECT DISTINCT source FROM public.\"ScriptResourceHistory\"", String.class),
                "the bootstrap's import records its source");
        // and the user: the run went through the command as the sysadmin (Order 5, after the database was emptied),
        // whose security context the jobs run on
        assertEquals(List.of("sysadmin"), jdbc.queryForList("SELECT DISTINCT coalesce(u.username, 'none') "
                        + "FROM public.\"ScriptResourceHistory\" h LEFT JOIN public.\"User\" u ON h.user_id = u.id",
                String.class), "every history row names the sysadmin who ran the bootstrap");
        assertEquals(45, count("Category"), "the base product's 45 categories");
        assertEquals(List.of("Light", "Light buoy", "Buoy", "Beacon", "Obstruction", "Drifting object",
                        "Radio navigation", "Firing Exercises"),
                jdbc.queryForList("SELECT d.name FROM public.\"Category\" c JOIN public.\"CategoryDesc\" d "
                        + "ON d.entity_id = c.id AND d.lang = 'en' WHERE c.parent_id IS NULL "
                        + "ORDER BY c.\"siblingSortOrder\", c.id", String.class),
                "the eight roots by their sort order");
        assertEquals(0, count("Category_Domain"), "no category is linked to a domain");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.\"Category\" WHERE \"messageId\" IS NOT NULL",
                Integer.class), "no category names a message");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.\"Category_scriptResourcePaths\" p "
                        + "LEFT JOIN public.\"ScriptResource\" s ON s.path = p.\"scriptResourcePaths\" "
                        + "WHERE s.id IS NULL", Integer.class),
                "every script a category names is a template of the bootstrap");
        assertNotNull(categoryService.getFiringExercisesCategory(), "found by its English name");
        assertEquals(List.of("draft", "public", "standard"), jdbc.queryForList(
                "SELECT \"reportId\" FROM public.\"FmReport\" ORDER BY \"reportId\"", String.class));
        assertNotNull(fmReportService.getPublicReport());
        assertEquals(32, count("ParamType"), "the parameter types that are not standard");
        assertEquals(39, templateExecutionService.getParamTypes().size(), "with the 7 standard ones from code");
        assertEquals(List.of("audio", "navtex", "twitter"), jdbc.queryForList(
                "SELECT \"typeId\" FROM public.\"PromulgationType\" ORDER BY \"typeId\"", String.class));
        assertEquals(111, jdbc.queryForObject("SELECT count(*) FROM public.\"DictionaryEntry\" e "
                + "JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id WHERE d.name = 'aton'", Integer.class));

        // English only (C6): a country's bootstrap adds its languages in the same run
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.\"DictionaryEntryDesc\" x "
                + "JOIN public.\"DictionaryEntry\" e ON x.entity_id = e.id "
                + "JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id WHERE d.name = 'aton' AND x.lang = 'da'",
                Integer.class), "no Danish description of the aton dictionary");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.\"CategoryDesc\" WHERE lang = 'da'",
                Integer.class), "no Danish category name");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.\"TemplateParamDesc\" WHERE lang = 'da'",
                Integer.class), "no Danish template-parameter name, of a category or a parameter type");
    }

    @Test
    @Order(7)
    void appliedIsRefused() {
        BootstrapRefusedException e = assertThrows(BootstrapRefusedException.class,
                () -> runner.run(List.of(archive("niord-bootstrap.zip", baseBootstrap()))));
        assertEquals(BootstrapRefusedException.STATE, e.getStatus());
        assertTrue(e.getMessage().contains("'applied'"), e.getMessage());
        given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .multiPart("archive", "niord-bootstrap.zip", baseBootstrap(), "application/zip")
                .when().post(BOOTSTRAP)
                .then().statusCode(409).body("detail", containsString("'applied'"));
        assertEquals(1, count("Domain"), "nothing was applied twice");
        assertEquals(80, count("ScriptResource"));
    }

    /**
     * A country's archive after the base product's may leave the master domain inactive by restating it with its
     * domainId and active false only (the owner's ruling of 2026-11-03): within the first bootstrap that flag wins, the
     * rest of the master domain is kept, and the country's own domain is active
     */
    @Test
    @Order(8)
    void aCountryArchiveMayLeaveTheMasterDomainInactive() {
        emptyTheDatabase();

        BootstrapRunner.Outcome outcome = runner.run(List.of(archive("niord-bootstrap.zip", baseBootstrap()),
                archive("country-master-inactive.zip", BootstrapArchiveTest.zipFolder(
                        BootstrapArchiveTest.FIXTURES.resolve("country-master-inactive")))));

        assertTrue(outcome.applied(), "the bootstrap is applied: " + outcome.error());
        assertEquals(List.of("it-country", "niord-client-master"), jdbc.queryForList(
                "SELECT \"domainId\" FROM public.\"Domain\" ORDER BY \"domainId\"", String.class));
        assertEquals(List.of(false), jdbc.queryForList(
                "SELECT active FROM public.\"Domain\" WHERE \"domainId\" = 'niord-client-master'", Boolean.class),
                "the country's restated flag wins");
        assertEquals(List.of("Master"), jdbc.queryForList(
                "SELECT name FROM public.\"Domain\" WHERE \"domainId\" = 'niord-client-master'", String.class),
                "nothing else of the master domain changed: the restatement gives no name");
        assertEquals(List.of(true), jdbc.queryForList(
                "SELECT active FROM public.\"Domain\" WHERE \"domainId\" = 'it-country'", Boolean.class));
    }
}
