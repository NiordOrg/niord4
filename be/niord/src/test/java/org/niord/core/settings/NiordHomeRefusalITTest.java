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
package org.niord.core.settings;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.niord.core.db.JpaJsonAttributeConverter;
import org.niord.test.ITTest;
import org.niord.test.NiordTestApplication;
import org.niord.test.NiordTestContainers;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A start whose {@code niord.home} names no folder is refused: the whole application is started, as a
 * deployment starts it, on a fresh database of its own, and the start stops at {@link SettingsService#init()} with one
 * text that names the cause and the cure, logged at ERROR and carried by the exception, before any setting is added.
 *
 * <p>The home's sources are the JVM's system properties and environment variables, which this class sets and puts
 * back around each start. A blank {@code niord.home} property (what {@code -Dniord.home=$NIORD_HOME} gives when nothing
 * sets the variable) is refused on any machine. The two starts on the token {@code ${NIORD_HOME}} need a JVM without
 * an exported {@code NIORD_HOME} environment variable, which wins over the database's row and the settings file, and
 * are skipped where one is exported (on CI none is).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NiordHomeRefusalITTest {

    /** A fresh, un-seeded PostGIS container of its own, as SettingsFreshDatabaseITTest has */
    private static final PostgreSQLContainer<?> FRESH_POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("imresamu/postgis:18-3.6")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("niord")
                    .withUsername("niord")
                    .withPassword("niord");

    private static final String HOME = "niord.home";

    private static final String HOME_VARIABLE = "NIORD_HOME";

    private static final String CURE =
            "; set the environment variable NIORD_HOME or start with -Dniord.home=<folder>";

    static {
        // ITTest's static bootstrap: twitter4j's restBaseURL, frozen before any Spring context is built
        try {
            Class.forName(ITTest.class.getName(), true, NiordHomeRefusalITTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(NiordTestContainers.dockerAvailable(),
                "Docker is not available - skipping Niord integration tests");
        Assumptions.assumeTrue(System.getenv(HOME) == null, "a niord.home environment variable wins over every test");
    }

    @AfterAll
    static void stopTheDatabase() {
        if (FRESH_POSTGRES.isRunning()) {
            FRESH_POSTGRES.stop();
        }
    }

    @Test
    @Order(1)
    void aBlankHomeStopsTheStart() {
        String refusal = refusedStart("");

        // A niord.home property hides NIORD_HOME even when blank, so the cure is to give it a folder
        assertEquals("niord.home cannot be resolved: its value is \"\" (from the niord.home system property or "
                + "environment variable), which names no folder; give the niord.home system property or environment "
                + "variable a folder (NIORD_HOME is not read while niord.home is set)", refusal);
    }

    @Test
    @Order(2)
    void theSettingsFilesUnresolvedTokenStopsTheStart() throws Exception {
        requireNoHomeVariable();

        // The test tree's niord.json, read as a country's settings file, gives niord.home = ${NIORD_HOME}
        String refusal = refusedStart(null);

        assertEquals("niord.home cannot be resolved: its value is \"${NIORD_HOME}\" (from the settings file) and no "
                + "NIORD_HOME is set" + CURE, refusal);
        assertEquals(0, settingRows(), "a refused start adds no setting");
    }

    @Test
    @Order(3)
    void theDatabasesUnresolvedTokenStopsTheStart() throws Exception {
        requireNoHomeVariable();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("INSERT INTO public.\"Setting\" "
                + "(setting_key, cached, description, editable, type, setting_value, web) "
                + "VALUES ('niord.home', true, 'The root directory for the Niord application', true, 'String', ?, "
                + "false)")) {
            ps.setString(1, new JpaJsonAttributeConverter().convertToDatabaseColumn("${NIORD_HOME_OF_THE_ROW}/home"));
            ps.executeUpdate();
        }

        String refusal = refusedStart(null);

        assertEquals("niord.home cannot be resolved: its value is \"${NIORD_HOME_OF_THE_ROW}/home\" (from the "
                + "database's niord.home setting) and no NIORD_HOME_OF_THE_ROW is set" + CURE, refusal);
        assertEquals(1, settingRows(), "a refused start adds no setting");
    }

    private static void requireNoHomeVariable() {
        Assumptions.assumeTrue(System.getenv(HOME_VARIABLE) == null || System.getenv(HOME_VARIABLE).isBlank(),
                "an exported NIORD_HOME wins over the token; run without one to start on the token");
    }

    /**
     * Starts the application with the {@code niord.home} system property set to the given value (null: none) and
     * no {@code NIORD_HOME} property, asserts that the start fails with the refusal logged at ERROR, and returns
     * the refusal's text, as the exception carries it
     */
    private static String refusedStart(String home) {
        String savedHome = System.getProperty(HOME);
        String savedVariable = System.getProperty(HOME_VARIABLE);
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        Logger logger = (Logger) LoggerFactory.getLogger(SettingsService.class);
        try {
            set(HOME, home);
            System.clearProperty(HOME_VARIABLE);
            SpringApplication application = new SpringApplication(NiordTestApplication.class);
            application.setBannerMode(Banner.Mode.OFF);
            application.setRegisterShutdownHook(false);
            // After the logging system is initialised, before any bean is created
            application.addInitializers(context -> logger.addAppender(log));
            Throwable failure = assertThrows(Throwable.class, () -> application.run(arguments()).close());

            IllegalStateException refusal = null;
            for (Throwable t = failure; t != null && refusal == null; t = t.getCause()) {
                if (t instanceof IllegalStateException e && String.valueOf(e.getMessage())
                        .startsWith("niord.home cannot be resolved")) {
                    refusal = e;
                }
            }
            assertTrue(refusal != null, "the start failed, but not with the refusal: " + failure);
            String text = refusal.getMessage();
            List<String> errors = log.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertEquals(List.of(text), errors, "the refusal is logged at ERROR, as the exception's text");
            return text;
        } finally {
            logger.detachAppender(log);
            set(HOME, savedHome);
            set(HOME_VARIABLE, savedVariable);
        }
    }

    private static void set(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /** The properties ITTest's start gives the application, on this class's own database */
    private static String[] arguments() {
        PostgreSQLContainer<?> db = freshPostgres();
        return new String[] {
                "--server.port=0",
                "--spring.datasource.url=" + db.getJdbcUrl(),
                "--spring.datasource.username=" + db.getUsername(),
                "--spring.datasource.password=" + db.getPassword(),
                "--spring.jpa.properties.jakarta.persistence.validation.mode=none",
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + NiordTestContainers.keycloakRealmUrl(),
                "--spring.mail.host=" + NiordTestContainers.smtpHost(),
                "--spring.mail.port=" + NiordTestContainers.smtpPort(),
                "--niord.smtp.host=" + NiordTestContainers.smtpHost(),
                "--niord.smtp.port=" + NiordTestContainers.smtpPort(),
                "--niord.schedule.auto-publish-cron=-",
                "--niord.schedule.auto-expire-cron=-",
                "--niord.schedule.firing-exercise-cron=-",
                "--niord.mailinglist.status-change-sweep-cron=-"
        };
    }

    private static PostgreSQLContainer<?> freshPostgres() {
        if (!FRESH_POSTGRES.isRunning()) {
            FRESH_POSTGRES.start();
        }
        return FRESH_POSTGRES;
    }

    private static Connection connection() throws Exception {
        PostgreSQLContainer<?> db = freshPostgres();
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private static int settingRows() throws Exception {
        try (Connection c = connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM public.\"Setting\"")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
