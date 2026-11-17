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

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * Central, reusable definitions of the external services the Niord application needs at runtime,
 * started with Testcontainers and shared across every integration test in this module.
 * <p>
 * The images and credentials mirror the root {@code docker-compose.yml}, but the containers bind
 * to <em>dynamic</em> host ports, so integration tests never depend on a developer's local
 * Docker-Compose stack or on fixed host ports.
 * <p>
 * Containers use the Testcontainers "singleton container" pattern: each is created once and started
 * lazily on first access, then reused for the whole test run. Testcontainers' Ryuk sidecar stops and
 * removes them when the JVM exits, so there is no explicit shutdown to manage.
 * <p>
 * NOTE: this singleton pattern assumes a single test JVM. Do NOT enable Failsafe forking
 * ({@code forkCount > 1}) or parallel execution for the integration tests: each fork would start its
 * own copy of these containers. The integration tests run on dynamic ports, so multiple forks would
 * not collide on host ports, but they would waste resources and complicate Ryuk cleanup. The default
 * Failsafe configuration (single fork, no parallelism) is what this relies on.
 * <p>
 * This is intentionally framework-neutral (plain Testcontainers, no Quarkus types) so the same
 * coordinates can feed either a plain integration test or a future {@code @QuarkusTest} (via
 * {@link NiordContainersResource}).
 */
public final class NiordTestContainers {

    // Images pinned to the versions used by the root docker-compose.yml.
    // Multi-arch (arm64 + amd64) PostGIS 3.6 / PostgreSQL 18. The official postgis/postgis:18-* images are
    // amd64-only; imresamu/postgis is a faithful multi-arch rebuild that also runs natively on Apple Silicon.
    private static final String POSTGRES_IMAGE = "imresamu/postgis:18-3.6";
    private static final String ARTEMIS_IMAGE  = "apache/activemq-artemis:2.31.2";
    private static final String MAILHOG_IMAGE  = "mailhog/mailhog:v1.0.1";
    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:22.0.5";

    private static final String NIORD = "niord";

    // Static test fixtures on the test classpath (niord/src/test/resources).
    // The PostgreSQL container starts EMPTY: the schema is built by Liquibase (the production V001
    // baseline) when the application starts, and the fixture (db/niord-base-data.sql, the base product's
    // bootstrap and the sample) is then layered on top — see db/changelog/changelog-with-seed.yaml and ITTest.
    // The Keycloak realm is derived from the DK bootstrap realm (niord-bootstrap-realm-dk.json) with its five domain
    // clients renamed to the sample's domains (niord-nw, niord-nm, niord-fa, niord-almanac, niord-annex;
    // niord-client-master kept); docs/test-infrastructure.md, "Keycloak realm import", says what it holds.
    private static final String REALM_JSON_RESOURCE = "keycloak/niord-realm.json";

    // Container-internal ports.
    private static final int ARTEMIS_AMQP_PORT  = 5672;   // qpid-jms (AMQP) acceptor
    private static final int ARTEMIS_CORE_PORT  = 61616;  // Artemis core protocol
    private static final int ARTEMIS_CONSOLE    = 8161;   // web console
    private static final int MAILHOG_SMTP_PORT  = 1025;
    private static final int MAILHOG_HTTP_PORT  = 8025;
    private static final int KEYCLOAK_HTTP_PORT = 8080;

    // ------------------------------------------------------------------------------------------
    // Singleton containers (created at class load, started lazily, reaped by Ryuk at JVM exit).
    // Creating the objects does NOT touch Docker; only start() does.
    // ------------------------------------------------------------------------------------------

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE)
                            // postgis/postgis is a drop-in superset of the official postgres image.
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(NIORD)
                    .withUsername(NIORD)
                    .withPassword(NIORD)
                    // The suite runs in ONE JVM and Spring caches every distinct test context, each holding a
                    // Hikari pool of the default 10 connections against this one database. Ten live contexts
                    // meet PostgreSQL's default max_connections of 100 exactly, and the eleventh caller reads
                    // "FATAL: sorry, too many clients already" -- which is how TestInfrastructureITTest failed
                    // on CI the day IssueRenderRealITTest added a context (2026-09-15). Raised well clear of
                    // the count; fsync=off is PostgreSQLContainer's own default command, kept here because
                    // withCommand replaces it.
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300");
                    // No init script: the container starts EMPTY. The application builds the schema with
                    // Liquibase when it starts (the production V001 baseline + PostGIS extension) and then
                    // layers the base-data seed on top — see db/changelog/changelog-with-seed.yaml and ITTest.
                    // The PostGIS extension is pre-installed in the image, and V001 issues CREATE EXTENSION IF
                    // NOT EXISTS postgis, so the schema's geometry columns resolve.

    private static final GenericContainer<?> ARTEMIS =
            new GenericContainer<>(DockerImageName.parse(ARTEMIS_IMAGE))
                    .withExposedPorts(ARTEMIS_AMQP_PORT, ARTEMIS_CORE_PORT, ARTEMIS_CONSOLE)
                    .withEnv("ARTEMIS_USER", NIORD)
                    .withEnv("ARTEMIS_PASSWORD", NIORD)
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofSeconds(120)));

    private static final GenericContainer<?> MAILHOG =
            new GenericContainer<>(DockerImageName.parse(MAILHOG_IMAGE))
                    .withExposedPorts(MAILHOG_SMTP_PORT, MAILHOG_HTTP_PORT)
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofSeconds(60)));

    private static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>(DockerImageName.parse(KEYCLOAK_IMAGE))
                    .withExposedPorts(KEYCLOAK_HTTP_PORT)
                    .withEnv("KEYCLOAK_ADMIN", "admin")
                    .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                    // Import the Niord 'niord' realm (its clients include niord-client-master, the OIDC
                    // client the application authenticates with) so integration tests run against a
                    // valid Keycloak, mirroring the root docker-compose.yml. The realm JSON is a static
                    // copy of niord-bootstrap-realm-dk.json (from the original niord-dk deployment) under the test resources.
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource(REALM_JSON_RESOURCE),
                            "/opt/keycloak/data/import/niord-realm.json")
                    .withCommand("start-dev", "--import-realm", "--http-relative-path=/auth")
                    // Wait on the imported 'niord' realm's OIDC discovery doc: a deterministic readiness
                    // signal that also proves the import finished. Realm import adds startup time, so the
                    // ceiling is generous - it exists to catch a genuine hang, not as an expected duration.
                    .waitingFor(Wait.forHttp("/auth/realms/niord/.well-known/openid-configuration")
                            .forPort(KEYCLOAK_HTTP_PORT)
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(300)));

    private NiordTestContainers() {
    }

    /**
     * True when a usable Docker environment is available; integration tests skip otherwise.
     * Testcontainers caches this result, so if Docker becomes unreachable mid-build the tests skip
     * rather than fail — ensure the Docker daemon stays healthy for the whole CI run.
     */
    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    // --- PostgreSQL / PostGIS -----------------------------------------------------------------

    public static PostgreSQLContainer<?> postgres() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        return POSTGRES;
    }

    public static String dbJdbcUrl()  { return postgres().getJdbcUrl(); }
    public static String dbUsername() { return postgres().getUsername(); }
    public static String dbPassword() { return postgres().getPassword(); }

    // --- ActiveMQ Artemis ---------------------------------------------------------------------

    public static GenericContainer<?> artemis() {
        if (!ARTEMIS.isRunning()) {
            ARTEMIS.start();
        }
        return ARTEMIS;
    }

    /** AMQP broker URL for the qpid-jms client, e.g. {@code amqp://localhost:32769}. */
    public static String brokerAmqpUrl() {
        GenericContainer<?> mq = artemis();
        return String.format("amqp://%s:%d", mq.getHost(), mq.getMappedPort(ARTEMIS_AMQP_PORT));
    }

    public static String brokerUsername() { return NIORD; }
    public static String brokerPassword() { return NIORD; }

    // --- SMTP (Mailhog) -----------------------------------------------------------------------

    public static GenericContainer<?> mailhog() {
        if (!MAILHOG.isRunning()) {
            MAILHOG.start();
        }
        return MAILHOG;
    }

    public static String smtpHost() { return mailhog().getHost(); }
    public static int    smtpPort() { return mailhog().getMappedPort(MAILHOG_SMTP_PORT); }

    /** Host port of the Mailhog HTTP API (the v1/v2 REST interface used to read captured mail). */
    public static int mailhogHttpPort() { return mailhog().getMappedPort(MAILHOG_HTTP_PORT); }

    /** Base URL of the Mailhog HTTP API, e.g. {@code http://localhost:32771}. */
    public static String mailhogHttpUrl() {
        GenericContainer<?> mh = mailhog();
        return String.format("http://%s:%d", mh.getHost(), mh.getMappedPort(MAILHOG_HTTP_PORT));
    }

    // --- Keycloak -----------------------------------------------------------------------------

    public static GenericContainer<?> keycloak() {
        if (!KEYCLOAK.isRunning()) {
            KEYCLOAK.start();
        }
        return KEYCLOAK;
    }

    /** Base auth-server URL with the /auth relative path, e.g. {@code http://localhost:32770/auth}. */
    public static String keycloakAuthServerUrl() {
        GenericContainer<?> kc = keycloak();
        return String.format("http://%s:%d/auth", kc.getHost(), kc.getMappedPort(KEYCLOAK_HTTP_PORT));
    }

    /**
     * OIDC auth-server URL of the imported {@code niord} realm, e.g.
     * {@code http://localhost:32770/auth/realms/niord}. This is what the application reads as
     * {@code NIORD-KEYCLOAK-URL} ({@code quarkus.oidc.auth-server-url}).
     */
    public static String keycloakRealmUrl() {
        return keycloakAuthServerUrl() + "/realms/niord";
    }
}
