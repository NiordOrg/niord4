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

import io.restassured.RestAssured;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.niord.core.message.ScheduledMessageService;
import org.niord.core.schedule.FiringExerciseService;
import org.niord.core.util.CdiUtils;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Shared base class for Niord integration tests.
 * <p>
 * Integration tests follow the {@code *ITTest} naming convention and are executed by the Maven
 * Failsafe plugin during {@code mvn verify} (unit tests, named {@code *Test}, are executed by
 * Surefire during {@code mvn test}). Concrete integration tests extend this class and obtain
 * service coordinates through the accessors below, each of which lazily starts the corresponding
 * shared container defined in {@link NiordTestContainers}.
 * <p>
 * The class is {@code abstract} on purpose: although its name matches the {@code *ITTest} Failsafe
 * include pattern, Surefire and Failsafe never instantiate abstract classes, so the base class is
 * not run as a test itself.
 * <p>
 * It deliberately holds only generic, framework-neutral infrastructure — a Docker-availability
 * guard and container accessors — and no business logic, so future integration tests stay easy to
 * write and as framework-agnostic as practical (a later Quarkus-to-Spring migration should not
 * need to rewrite the tests that extend this class).
 */
@SpringBootTest(classes = NiordTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Switch off the two status-mutating ScheduledMessageService sweeps for every integration test
        // ("-" = Spring's Scheduled.CRON_DISABLED); see requireMessageStatusCronsDisabled() below for why,
        // and for the guard that fails the suite if this ever stops taking effect. Declared here rather
        // than in an application.yml because that file is module-local (and excluded from the niord-base
        // test-jar), while this annotation reaches every IT in every module through the shared base class.
        "niord.schedule.auto-publish-cron=-",
        "niord.schedule.auto-expire-cron=-",
        // The hourly firing-exercise run takes every active schedule in the database, and the firing tests
        // commit theirs; the tests call FiringExerciseService.updateFiringExercises(ids) themselves.
        "niord.schedule.firing-exercise-cron=-"
})
public abstract class ITTest {

    static {
        // The test tree's niord.json, which the IT start writes as settings (the fixture holds no Setting row),
        // references ${NIORD_HOME}; SettingsService resolves such tokens from an environment variable or system
        // property (else its expander live-locks — the documented S1 landmine). Provide a writable temp
        // directory, mirroring the NIORD_HOME the Quarkus deployment set.
        if (System.getenv("NIORD_HOME") == null && System.getProperty("NIORD_HOME") == null) {
            System.setProperty("NIORD_HOME", System.getProperty("java.io.tmpdir") + "/niord");
        }
        emptyTheBatchJobFolders();

        // Start the Twitter API stub and bind twitter4j's restBaseURL system property BEFORE any Spring
        // context (and therefore any TwitterFactory/twitter4j) is built. twitter4j 4.0.6 freezes that
        // property in a static singleton on first class-load, so it must be set this early, from the base
        // class every integration test loads. See TwitterApiStub for the full rationale.
        TwitterApiStub.bootstrap();
    }

    /**
     * Empties {@code NIORD_HOME/batch-jobs} (the default {@code batchJobRootPath}, {@code ${niord.home}/batch-jobs})
     * once per test JVM, before any context starts. A job's log files lie in
     * {@code batch-jobs/<job>/execution/<year>/<month>/<job number>} and are appended to, and the job numbers (the
     * {@code BATCH_JOB_*} sequences) start again with every fresh database; the home itself is kept between local
     * runs ({@code java.io.tmpdir/niord}, or an exported {@code NIORD_HOME}, whose job logs are therefore deleted
     * too). Without this, a job's log could hold an earlier run's lines: a line an assertion looks for could come
     * from that run, and a failure it wrote could break a test that asserts a silent log (the import round trips,
     * detector check of 2026-11-12). CI and {@code verify-local} start from an empty home, so for them this deletes
     * nothing. Only the batch-job folder is touched; the rest of the home is used as it is.
     *
     * <p>The home is found in the application's own order ({@code SettingsService.overrideOf}): the
     * {@code niord.home} system property, the {@code niord.home} environment variable, then the {@code NIORD_HOME}
     * system property and environment variable, each of the last two only when not blank. Another order would empty
     * a folder the application does not use and leave the stale logs in the one it does.
     */
    private static void emptyTheBatchJobFolders() {
        String home = System.getProperty("niord.home");
        if (home == null) {
            home = System.getenv("niord.home");
        }
        if (home == null && System.getProperty("NIORD_HOME") != null && !System.getProperty("NIORD_HOME").isBlank()) {
            home = System.getProperty("NIORD_HOME").trim();
        }
        if (home == null && System.getenv("NIORD_HOME") != null && !System.getenv("NIORD_HOME").isBlank()) {
            home = System.getenv("NIORD_HOME").trim();
        }
        if (home == null || home.isBlank()) {
            return;
        }
        Path batchJobs = Path.of(home, "batch-jobs");
        if (!Files.isDirectory(batchJobs)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(batchJobs)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            System.err.println("ITTest: could not empty " + batchJobs + ", a job log may hold an earlier run's lines: "
                    + e);
        }
    }

    @LocalServerPort
    protected int port;

    /**
     * Skip (rather than fail) the whole integration-test class when Docker is unavailable, so that
     * {@code mvn verify} still succeeds on a machine or CI runner without Docker. The integration
     * tests require Docker; see {@code docs/test-infrastructure.md}.
     */
    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(NiordTestContainers.dockerAvailable(),
                "Docker is not available - skipping Niord integration tests");
    }

    /**
     * Point the Spring Boot application at the shared Testcontainers (started lazily on first
     * access). Replaces the Quarkus {@code NiordContainersResource} lifecycle manager: instead of
     * env-style {@code NIORD-*} keys read by application.properties, we bind the equivalent Spring
     * properties.
     * <p>
     * The database: the container starts empty and Liquibase builds the schema with the production master
     * changelog when the application starts, then the fixture is layered on top. See
     * {@code db/changelog/changelog-with-seed.yaml}, a test resource kept in the test-jar. The fixture is a dump of
     * a database bootstrapped with the base product's archive and loaded with the sample
     * (niord/scripts/regen-test-seed-postgres.sh), so it carries the base product's 80 templates and scripts, its
     * three reports and the sample's rows. No deployment loads it: a new deployment's data comes from the
     * bootstrap (docs/code-data-config-plan.md §7.4), and the seeded database reads {@code existing} to the
     * bootstrap marker.
     */
    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", NiordTestContainers::dbJdbcUrl);
        registry.add("spring.datasource.username", NiordTestContainers::dbUsername);
        registry.add("spring.datasource.password", NiordTestContainers::dbPassword);
        // Exercise the real production schema path unchanged: Liquibase runs automatically when the app
        // starts (it is enabled by default — there is no enabled flag) and Hibernate validates the entity
        // mappings against the result (ddl-auto=validate, the application.yml default). The only deviation
        // tests need is the changelog: point Liquibase at the test changelog that applies the production
        // master and THEN layers the committed base-data seed (db/niord-base-data.sql, a test resource) on
        // top as a final changeset — "Liquibase first, dump on top". Only the integration tests use it,
        // here and in the countries' ITs through the test-jar. A V001/entity drift therefore fails every
        // IT, not just LiquibaseBootstrapITTest.
        registry.add("spring.liquibase.change-log",
                () -> "classpath:db/changelog/changelog-with-seed.yaml");
        // The original had no Hibernate Validator: JPA @NotNull/@Size were not enforced on persist.
        registry.add("spring.jpa.properties.jakarta.persistence.validation.mode", () -> "none");
        // OIDC: validate bearer tokens against the Keycloak test realm.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", NiordTestContainers::keycloakRealmUrl);
        // Mail goes to MailHog.
        registry.add("spring.mail.host", NiordTestContainers::smtpHost);
        registry.add("spring.mail.port", () -> NiordTestContainers.smtpPort());
        registry.add("niord.smtp.host", NiordTestContainers::smtpHost);
        registry.add("niord.smtp.port", () -> NiordTestContainers.smtpPort());
    }

    @Autowired
    PlatformTransactionManager transactionManager;

    /** Bind RestAssured to the random server port (the ITs already include the {@code /rest} prefix,
     *  so the base path stays empty) and bind the {@link Tx} REQUIRES_NEW transaction template. */
    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.basePath = "";
        Tx.init(transactionManager);
    }

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Points {@link CdiUtils}'s static application context at this test's context. {@code CdiUtils} keeps the context
     * in a static field, set once when a context starts, and the bootstrap templates' scripts reach the services
     * through it ({@code templates/tmpl/common.js}, the directives). A deployment has one context; the suite has
     * several (a class with {@code @MockitoSpyBean} or properties of its own gets one), and the static field keeps
     * the last one started: once that class's context is closed ({@code @DirtiesContext}) every later test on the
     * shared context that executes a template with a script got "the application context has been closed already",
     * answered as 409 by the execute endpoint, and a NAVTEX text without its area header
     * (BootstrapTemplateMatrixITTest in the whole run of 2026-11-12, after DeliveryFaultInjectionITTest and the
     * other classes with a context of their own).
     */
    @BeforeEach
    void pointCdiUtilsAtThisTestsContext() {
        applicationContext.getBeanProvider(CdiUtils.class)
                .ifAvailable(cdiUtils -> cdiUtils.setApplicationContext(applicationContext));
    }

    // Convenience accessors. Each lazily starts the relevant shared container on first use.

    protected static String dbJdbcUrl()  { return NiordTestContainers.dbJdbcUrl(); }
    protected static String dbUsername() { return NiordTestContainers.dbUsername(); }
    protected static String dbPassword() { return NiordTestContainers.dbPassword(); }

    protected static String brokerAmqpUrl()  { return NiordTestContainers.brokerAmqpUrl(); }
    protected static String brokerUsername() { return NiordTestContainers.brokerUsername(); }
    protected static String brokerPassword() { return NiordTestContainers.brokerPassword(); }

    protected static String smtpHost() { return NiordTestContainers.smtpHost(); }
    protected static int    smtpPort() { return NiordTestContainers.smtpPort(); }

    protected static String mailhogHttpUrl() { return NiordTestContainers.mailhogHttpUrl(); }

    protected static String keycloakAuthServerUrl() { return NiordTestContainers.keycloakAuthServerUrl(); }

    // ------------------------------------------------------------------------------------------- //
    // Access tokens
    //
    // A single, TTL-aware cache of real Keycloak access tokens, shared by every integration test that
    // drives a secured REST endpoint. Each REST IT previously hand-rolled its own static token cache;
    // centralising it here removes that duplication and, crucially, bounds the cache lifetime: the
    // realm's access tokens live 300s (see src/test/resources/keycloak/niord-realm.json), so a token is
    // re-minted once it is older than TOKEN_TTL_NANOS rather than being reused for the whole (much
    // longer) test run. Only {@link KeycloakTestTokens} is Keycloak-aware; the cache itself is
    // framework-neutral and survives the Spring migration.
    // ------------------------------------------------------------------------------------------- //

    /** Re-mint a cached token once it is older than this — kept comfortably under the realm's 300s lifespan. */
    private static final long TOKEN_TTL_NANOS = 240_000_000_000L;

    private record CachedToken(String token, long mintedNanos) {}

    private static final Map<String, CachedToken> TOKEN_CACHE = new ConcurrentHashMap<>();

    /** A bearer access token for the {@code sysadmin} user — the common case for write-authorized tests. */
    protected static String accessToken() {
        return accessToken("sysadmin");
    }

    /**
     * A bearer access token for the given role tier — one of {@code sysadmin}, {@code admin},
     * {@code editor} or {@code user}. The token is cached and transparently re-minted before it can
     * expire. See {@link KeycloakTestTokens} for the role/identity matrix the tiers map onto.
     */
    protected static String accessToken(String role) {
        CachedToken cached = TOKEN_CACHE.get(role);
        if (cached == null || System.nanoTime() - cached.mintedNanos() > TOKEN_TTL_NANOS) {
            cached = new CachedToken(mintToken(role), System.nanoTime());
            TOKEN_CACHE.put(role, cached);
        }
        return cached.token();
    }

    private static String mintToken(String role) {
        return switch (role) {
            case "sysadmin" -> KeycloakTestTokens.sysadminAccessToken();
            case "admin"    -> KeycloakTestTokens.adminAccessToken();
            case "editor"   -> KeycloakTestTokens.editorAccessToken();
            case "user"     -> KeycloakTestTokens.userAccessToken();
            // The endpoint sweep's identities, whose roles sit on a domain client only (see KeycloakTestTokens)
            case "curator"        -> KeycloakTestTokens.curatorAccessToken();
            case "domainEditor"   -> KeycloakTestTokens.domainEditorAccessToken();
            case "adminElsewhere" -> KeycloakTestTokens.adminElsewhereAccessToken();
            default -> throw new IllegalArgumentException("Unknown role '" + role
                    + "' (expected sysadmin, admin, editor, user, curator, domainEditor or adminElsewhere)");
        };
    }

    // ------------------------------------------------------------------------------------------- //
    // Status-cron disabling
    // ------------------------------------------------------------------------------------------- //

    @Autowired
    private Environment environment;

    /**
     * Fails every integration test unless BOTH {@code ScheduledMessageService} sweeps (auto-publish at
     * second :07, auto-expire at :28) are switched off for the suite.
     *
     * <p>Both jobs mutate committed message state in the shared Testcontainers database once a minute:
     * auto-publish promotes every <em>committed</em> VERIFIED message whose {@code publishDateFrom} is
     * in the past, racing any test that must hold such a row across a commit (losing the race fails the
     * test's own publish with {@code Invalid status transition PUBLISHED -> PUBLISHED} — an HTTP 500
     * over REST — or fires downstream JMS listeners the test never asked for); auto-expire does the
     * mirror thing to PUBLISHED rows with a past {@code publishDateTo}. Any wall-clock dodge is
     * probabilistic: the app's cron jobs share one scheduler of four threads (SCHED3), so a job can
     * start seconds late when every thread is busy and scan right through a "dodged" window, and two
     * jobs can now run at the same time. Turning the schedules off is
     * the only deterministic option, and each is now a property — the {@code @TestPropertySource} on this
     * class sets {@code niord.schedule.auto-publish-cron} and {@code niord.schedule.auto-expire-cron} to
     * {@code -} ({@link Scheduled#CRON_DISABLED}), so the tasks are never registered with the scheduler
     * at all. No coverage is lost: {@code ScheduledMessageServiceITTest} and
     * {@code DeliveryFaultInjectionITTest} run both sweeps through their seams
     * ({@code publishDueMessages} and {@code expireDueMessages}, with the test's own domains) rather
     * than waiting for the timer. The hourly firing run is checked the same way, by
     * {@link #requireFiringExerciseCronDisabled()}, which this method calls last.
     *
     * <p>The check resolves the placeholder off each live {@code @Scheduled} annotation instead of
     * re-reading the property names, so it asserts what the scheduler would actually run: it fires if a
     * schedule is renamed on only one side, if the IT config stops disabling one, or if a third
     * {@code @Scheduled} method appears on the class carrying a schedule the suite does not control —
     * forcing a decision instead of silently running (or silently killing) the new job. See the
     * "background schedulers" landmine in docs/test-authoring-guide.md.
     */
    @BeforeEach
    void requireMessageStatusCronsDisabled() {
        int checked = 0;
        for (Method method : ScheduledMessageService.class.getDeclaredMethods()) {
            for (Scheduled scheduled : method.getAnnotationsByType(Scheduled.class)) {
                checked++;
                String cron = environment.resolvePlaceholders(scheduled.cron());
                if (!Scheduled.CRON_DISABLED.equals(cron)) {
                    throw new IllegalStateException(
                            "ScheduledMessageService." + method.getName() + " is scheduled on '" + cron
                            + "' during the IT suite — it must be disabled ('" + Scheduled.CRON_DISABLED
                            + "') by the @TestPropertySource on ITTest; see the background-schedulers "
                            + "landmine in docs/test-authoring-guide.md");
                }
            }
        }
        if (checked != 2) {
            throw new IllegalStateException(
                    "Expected exactly the 2 ScheduledMessageService cron methods (checkForPublishableMessages, "
                    + "checkForExpirablePublishedMessages) but found " + checked
                    + " — the class's @Scheduled methods changed; revisit this guard AND the "
                    + "background-schedulers landmine in docs/test-authoring-guide.md");
        }
        requireFiringExerciseCronDisabled();
    }

    /**
     * Fails every integration test unless the hourly firing-exercise run is switched off for the suite. That run
     * ({@code updateFiringExercises()}, at hh:21:50) takes every active firing schedule in the database, and the
     * firing tests commit theirs: a run that lands inside such a test creates a second exercise for an area, or
     * cancels the one the test is about to read. The tests call {@code updateFiringExercises(ids)} themselves.
     * The check resolves the live annotation, so a renamed property fails here instead of quietly switching the
     * job back on.
     */
    private void requireFiringExerciseCronDisabled() {
        Method method;
        try {
            method = FiringExerciseService.class.getDeclaredMethod("updateFiringExercises");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("FiringExerciseService.updateFiringExercises() is gone; revisit this "
                    + "guard AND the background-schedulers landmine in docs/test-authoring-guide.md", e);
        }
        for (Scheduled scheduled : method.getAnnotationsByType(Scheduled.class)) {
            String cron = environment.resolvePlaceholders(scheduled.cron());
            if (!Scheduled.CRON_DISABLED.equals(cron)) {
                throw new IllegalStateException(
                        "FiringExerciseService.updateFiringExercises is scheduled on '" + cron
                        + "' during the IT suite; it must be disabled ('" + Scheduled.CRON_DISABLED
                        + "') by the @TestPropertySource on ITTest; see the background-schedulers "
                        + "landmine in docs/test-authoring-guide.md");
            }
        }
    }
}
