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
package org.niord.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.niord.core.bootstrap.BootstrapMarker;
import org.niord.core.dictionary.DictionaryService;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the <em>fresh-deploy</em> database-bootstrap path on an <strong>empty</strong> database:
 * Liquibase builds the baseline schema ({@code db/changelog/changes/V001__baseline_schema.sql}) and
 * Hibernate then runs {@code ddl-auto=validate} against it, with <strong>no base data present</strong>.
 * <p>
 * Since the suite switched to "Liquibase first, dump on top", every {@link ITTest} subclass also runs
 * Liquibase ({@code enabled=true}, {@code ddl-auto=validate}) — but always with the fixture layered on top
 * (the base product's bootstrap and the sample, see {@code db/changelog/changelog-with-seed.yaml}). This test is the one that covers the
 * <em>empty</em> deployment: a brand-new install with no seed, where the schema must stand on its own and
 * start-up must write nothing but the texts, the settings and the bootstrap marker. Because
 * {@code V001} is a hand-maintained file that is not emitted by
 * {@code niord/scripts/regen-test-seed-postgres.sh}, it can silently drift from the JPA entity mappings;
 * this test (and now the seeded suite) is that guard.
 * <p>
 * It boots the full application against a <strong>fresh, un-seeded</strong> PostGIS database (a dedicated
 * container, separate from the seeded singleton in {@link NiordTestContainers}), as the database user a new
 * deployment has: the owner of its database, not a superuser ({@link ApplicationDatabaseUser}). Reaching the test body at
 * all proves Hibernate {@code validate} passed against the Liquibase-built schema — a {@code V001}/entity
 * divergence aborts context startup. The assertions then prove that Liquibase (not Hibernate) actually
 * built the schema, that the reserved-word / PascalCase quoted identifiers resolve, that no data is present
 * (the tables are empty without the seed), and that start-up writes no domain, category, report, parameter type or
 * script resource: since C4.1 (docs/code-data-config-plan.md S2) the built-in reports, the standard parameter
 * types' rows and the master domain are no longer created at start, and a new database gets its data from the
 * bootstrap, once. The bootstrap marker (V007) reads {@code pending}. It also proves the defaults ledger (V006) on
 * a fresh deployment: every base product English text is added at the first start and recorded with its layer as
 * the source, and nothing is adopted, since nothing was there before.
 * <p>
 * Deliberately does <em>not</em> extend {@link ITTest}: that base class binds the seeded singleton container
 * and the test changelog that loads base data, which would defeat the empty-database purpose. The
 * infrastructure wiring below is therefore duplicated from {@link ITTest} on purpose, diverging only in
 * using a fresh container and the bare production master changelog (no seed changeset).
 * <p>
 * Its second test pins the bootstrap claim's not-data list ({@link BootstrapMarker#NOT_DATA}): after the first start
 * and a first authenticated request, the claim finds no table that holds data, so a fresh database is bootstrapped.
 * It runs after the first, whose assertions need the users' table still empty.
 */
@SpringBootTest(classes = NiordTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiquibaseBootstrapITTest {

    // A FRESH, UN-SEEDED PostGIS container, distinct from the seeded singleton in NiordTestContainers: the
    // schema here must come from Liquibase (V001), not the seed dump. Same image as the rest of the suite so
    // the postgis extension the baseline's geometry columns need is pre-installed. Created here, started
    // lazily by freshPostgres() (see the Docker-skip note on the property suppliers below).
    private static final PostgreSQLContainer<?> FRESH_POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("imresamu/postgis:18-3.6")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("niord")
                    .withUsername("niord")
                    .withPassword("niord");

    static {
        // Mirror ITTest's static bootstrap: SettingsService needs ${NIORD_HOME} to resolve seeded tokens,
        // and twitter4j's restBaseURL must be frozen before any Spring context is built.
        // A blank NIORD_HOME is no home, as SettingsService reads it
        if (ITTest.homeVariable() == null) {
            System.setProperty("NIORD_HOME", System.getProperty("java.io.tmpdir") + "/niord");
        }
        // niord.home itself, the way -Dniord.home supplies it to a real deployment. The system property is what
        // SettingsService.peek consults first, before the database row. (Before SettingsService refused a home that
        // names no folder, an unresolvable home put the folders under it at the root of the disk: Windows created
        // them and Linux refused, so this context failed on CI on 2026-09-15 while it passed on a developer machine.)
        if (System.getProperty("niord.home") == null) {
            System.setProperty("niord.home", ITTest.homeVariable());
        }
        TwitterApiStub.bootstrap();
    }

    private static PostgreSQLContainer<?> freshPostgres() {
        if (!FRESH_POSTGRES.isRunning()) {
            FRESH_POSTGRES.start();
        }
        return FRESH_POSTGRES;
    }

    /** Skip (rather than fail) when Docker is unavailable, exactly like {@link ITTest}. */
    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(NiordTestContainers.dockerAvailable(),
                "Docker is not available - skipping Niord integration tests");
    }

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        // The suppliers are lazy: they start their containers only when the property is resolved during
        // context load, which happens after @BeforeAll requireDocker has run — so a Docker-less machine
        // skips cleanly instead of erroring on a container start.
        registry.add("spring.datasource.url", () -> freshPostgres().getJdbcUrl());
        // The application's own database user, as a new deployment has it: the owner of its database, not the
        // image's superuser (ApplicationDatabaseUser). The whole first start runs as that user.
        registry.add("spring.datasource.username", () -> ApplicationDatabaseUser.of(freshPostgres()).name());
        registry.add("spring.datasource.password", () -> ApplicationDatabaseUser.of(freshPostgres()).password());

        // THE POINT OF THIS TEST: exercise the real production bootstrap path on a fresh database, relying on
        // the production defaults unchanged — Liquibase runs automatically (no enabled flag) against the bare
        // master changelog (no seed changeset, since this test does NOT override change-log), and Hibernate
        // validates the entity mappings against the result (ddl-auto=validate is the application.yml default).
        // A V001/entity divergence fails context startup at validation, failing this test.

        // The original had no Hibernate Validator: JPA @NotNull/@Size were not enforced on persist.
        registry.add("spring.jpa.properties.jakarta.persistence.validation.mode", () -> "none");
        // OIDC: the resource server eagerly fetches the issuer metadata at startup, so Keycloak must be up.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", NiordTestContainers::keycloakRealmUrl);
        // Mail -> MailHog (mirrors ITTest; the sender is lazy, but bind it for fidelity).
        registry.add("spring.mail.host", NiordTestContainers::smtpHost);
        registry.add("spring.mail.port", () -> NiordTestContainers.smtpPort());
        registry.add("niord.smtp.host", NiordTestContainers::smtpHost);
        registry.add("niord.smtp.port", () -> NiordTestContainers.smtpPort());
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    BootstrapMarker marker;

    @LocalServerPort
    int port;

    @Test
    void liquibaseBuildsSchemaAndHibernateValidatePassesOnFreshDatabase() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {

            // (0) The application connected as a new deployment's user does: the owner of its database and not
            //     a superuser. Everything below, and the start-up before it, ran as that user, so a changeset or
            //     a start-up statement that needs a superuser fails this class.
            try (ResultSet rs = st.executeQuery(
                    "SELECT r.rolsuper, pg_get_userbyid(d.datdba) FROM pg_roles r, pg_database d "
                            + "WHERE r.rolname = current_user AND d.datname = current_database()")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1), "the application's database user must not be a superuser");
                assertEquals(ApplicationDatabaseUser.NAME, rs.getString(2),
                        "the application's database user owns its database");
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_tables "
                    + "WHERE schemaname = 'public' AND tablename = 'Message' AND tableowner = current_user")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "the tables Liquibase built belong to the application's user");
            }

            // (1) Liquibase ran: its tracking table records exactly the committed baseline changeset. This
            //     also proves the schema came from Liquibase, not from a Hibernate ddl-auto fallback.
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM databasechangelog WHERE id = '001-baseline-schema'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1),
                        "Liquibase should have applied exactly one 001-baseline-schema changeset");
            }

            // (2) The quoted, PascalCase / reserved-word tables resolve and were created EMPTY (no seed
            //     leaked in). "User" is a PostgreSQL reserved word and AtonNode carries a `user` column —
            //     the cases hibernate.globally_quoted_identifiers exists for.
            for (String table : new String[] {"Message", "User", "AtonNode", "Feature"}) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
                    assertTrue(rs.next(), "table \"" + table + "\" should exist");
                    assertEquals(0, rs.getInt(1),
                            "fresh Liquibase-built \"" + table + "\" must be empty (no seed dump loaded)");
                }
            }

            // (3) PostGIS is there — the schema's geometry columns need it. The image created it, as a superuser
            //     does for every deployment, and the baseline's CREATE EXTENSION IF NOT EXISTS found it.
            try (ResultSet rs = st.executeQuery(
                    "SELECT 1 FROM pg_extension WHERE extname = 'postgis'")) {
                assertTrue(rs.next(), "the postgis extension must be installed");
            }

            // (4) Start-up writes no data (S2): no built-in report, no standard parameter type row, no master
            //     domain, and no category or script resource. A new database gets them from the bootstrap.
            for (String table : new String[] {"Domain", "Category", "FmReport", "ParamType", "ScriptResource",
                    "ScriptResourceHistory"}) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "start-up must write no \"" + table + "\" row");
                }
            }

            // (4b) The bootstrap marker (V007) reads pending: the database held no domain, message or area
            try (ResultSet rs = st.executeQuery("SELECT state FROM \"BootstrapState\" WHERE id = 1")) {
                assertTrue(rs.next(), "the first start writes the marker");
                assertEquals("pending", rs.getString(1));
            }

            // (5) The defaults ledger's changeset ran (V006) and its table exists.
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM databasechangelog WHERE id = '006-defaults-ledger'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Liquibase should have applied the 006-defaults-ledger changeset");
            }

            // (6) Every base product English text was added at the first start, and recorded with its layer as the
            //     source base. Nothing was there before the first start, so nothing is adopted.
            for (String bundle : DictionaryService.DEFAULT_BUNDLES) {
                Properties defaults = new Properties();
                try (InputStream in = getClass().getClassLoader()
                        .getResourceAsStream("defaults/base/texts/en/" + bundle + ".properties")) {
                    defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
                Set<String> keys = new HashSet<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT e.dict_key FROM \"DictionaryEntryDesc\" t "
                        + "JOIN \"DictionaryEntry\" e ON t.entity_id = e.id JOIN \"Dictionary\" d "
                        + "ON e.dictionary_id = d.id WHERE d.name = ? AND t.lang = 'en'")) {
                    ps.setString(1, bundle);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            keys.add(rs.getString(1));
                        }
                    }
                }
                Set<String> missing = new HashSet<>(defaults.stringPropertyNames());
                missing.removeAll(keys);
                assertTrue(missing.isEmpty(), "base product '" + bundle + "' texts missing in en: " + missing);
            }
            // (6b) Each text by its source: the English texts all with the source base, the test tree having no
            //      English text of its own, so an English key from anywhere else fails here; and the Danish from the
            //      test tree's stand-in pack (defaults/packs/da/), the IT niord.json's model languages being da, en
            Set<String> sources = new HashSet<>();
            Map<String, String> englishSource = new HashMap<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT dictionary, \"key\", lang, source FROM \"DefaultsLedger\" WHERE kind = 'TEXT'")) {
                while (rs.next()) {
                    sources.add(rs.getString(4));
                    if ("en".equals(rs.getString(3))) {
                        englishSource.put(rs.getString(1) + ":" + rs.getString(2), rs.getString(4));
                    }
                }
            }
            assertEquals(Set.of("base", "pack:da"), sources,
                    "a fresh database records only added texts, never adopted ones, from the base product's English "
                            + "and the stand-in da pack");
            Set<String> notFromBase = new TreeSet<>();
            for (String bundle : DictionaryService.DEFAULT_BUNDLES) {
                Properties defaults = new Properties();
                try (InputStream in = getClass().getClassLoader()
                        .getResourceAsStream("defaults/base/texts/en/" + bundle + ".properties")) {
                    defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
                defaults.stringPropertyNames().stream()
                        .filter(key -> !"base".equals(englishSource.get(bundle + ":" + key)))
                        .forEach(key -> notFromBase.add(bundle + ":" + key + " ("
                                + englishSource.get(bundle + ":" + key) + ")"));
            }
            assertEquals(Set.of(), notFromBase, "every base product English text is recorded with the source base");
            Set<String> notBase = new TreeSet<>();
            englishSource.forEach((key, source) -> {
                if (!"base".equals(source)) {
                    notBase.add(key + " (" + source + ")");
                }
            });
            assertEquals(Set.of(), notBase, "every English text is recorded with the source base");
        }
    }

    /**
     * Start-up and a first authenticated request write no table the bootstrap claim counts as data: the claim would
     * otherwise refuse every fresh database. A table a start-up listener or a first request writes that is not on the
     * not-data list fails here, named.
     */
    @Test
    @Order(Order.DEFAULT + 1)
    void startUpAndAFirstRequestWriteNoTableTheClaimCountsAsData() {
        given().port(port).auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .when().get("/rest/bootstrap")
                .then().statusCode(200);

        assertEquals(Optional.empty(), marker.firstDataTable(),
                "a table written at start-up or by a first request that is not on the not-data list");
    }
}
