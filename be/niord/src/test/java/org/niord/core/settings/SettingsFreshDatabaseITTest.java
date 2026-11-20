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

import io.restassured.RestAssured;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.niord.core.keycloak.KeycloakIntegrationService;
import org.niord.test.ITTest;
import org.niord.test.NiordTestApplication;
import org.niord.test.NiordTestContainers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings of a brand-new deployment (docs/code-data-config-plan.md §5.3): the generic application started on an
 * empty database, with SECOM switched off, as {@code LiquibaseBootstrapITTest} starts it (its own container, the bare
 * master changelog, no seed). Start-up adds exactly the catalog's operator keys whose declaring code runs here, each
 * with the catalog's type, description and flags and the value the settings file gives it, and records each in the
 * ledger; a read before that writes nothing, so the settings file's value reaches the database.
 *
 * <p>The annotation default never shadows the file: {@code KeycloakIntegrationService} reads {@code authServerUrl} while the context starts. Until
 * C3b that read inserted the annotation default {@code /auth}, which shadowed the settings file's value for good; now
 * the row and the injected field both hold the file's {@code http://localhost:8080/auth}.
 *
 * <p>The settings file is the test tree's root-level {@code niord.json}, read as a country's settings file (the
 * transitional read).
 */
@SpringBootTest(classes = NiordTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettingsFreshDatabaseITTest {

    /** A fresh, un-seeded PostGIS container of its own, as LiquibaseBootstrapITTest has */
    private static final PostgreSQLContainer<?> FRESH_POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("imresamu/postgis:18-3.6")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("niord")
                    .withUsername("niord")
                    .withPassword("niord");

    static {
        // ITTest's static bootstrap, without its seeded container: NIORD_HOME (the test tree's niord.json sets
        // niord.home to ${NIORD_HOME}) and twitter4j's restBaseURL, frozen before any Spring context is built.
        // Unlike LiquibaseBootstrapITTest no niord.home system property is set: the settings file's value resolves
        // it before start-up adds the row.
        try {
            Class.forName(ITTest.class.getName(), true, SettingsFreshDatabaseITTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
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
        registry.add("spring.datasource.username", () -> freshPostgres().getUsername());
        registry.add("spring.datasource.password", () -> freshPostgres().getPassword());
        // The bare master changelog: this test does not override spring.liquibase.change-log
        registry.add("spring.jpa.properties.jakarta.persistence.validation.mode", () -> "none");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", NiordTestContainers::keycloakRealmUrl);
        registry.add("spring.mail.host", NiordTestContainers::smtpHost);
        registry.add("spring.mail.port", () -> NiordTestContainers.smtpPort());
        registry.add("niord.smtp.host", NiordTestContainers::smtpHost);
        registry.add("niord.smtp.port", () -> NiordTestContainers.smtpPort());
    }

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    SettingsService settingsService;

    @Autowired
    KeycloakIntegrationService keycloakIntegrationService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void startUpAddsTheCatalogsOperatorKeysWithTheirFlagsAndTheFilesValues() throws Exception {
        Map<String, SettingsCatalog.Entry> catalog = SettingsCatalog.read(
                getClass().getClassLoader().getResource(SettingsCatalog.BASE_RESOURCE)).byKey();
        Map<String, Object> rootFile = rootSettings();

        // The operator keys declared by code that runs with SECOM switched off
        Set<String> expected = catalog.values().stream()
                .filter(e -> e.scope() == Setting.Scope.operator)
                .filter(e -> e.declaredIn().stream().anyMatch(c -> !c.startsWith("org.niord.secom.")))
                .map(SettingsCatalog.Entry::key)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(55, expected.size(), "72 operator keys less the 17 SECOM keys: " + expected);

        Map<String, Map<String, Object>> rows = rows();
        assertEquals(expected, new TreeSet<>(rows.keySet()), "exactly the operator keys whose code runs here");

        for (String key : expected) {
            SettingsCatalog.Entry entry = catalog.get(key);
            Map<String, Object> row = rows.get(key);
            assertEquals(entry.type().name(), row.get("type"), key + ": type");
            assertEquals(entry.description(), row.get("description"), key + ": description");
            assertEquals(entry.cached(), row.get("cached"), key + ": cached");
            assertEquals(entry.web(), row.get("web"), key + ": web");
            assertEquals(entry.editable(), row.get("editable"), key + ": editable");
            Object value = rootFile.containsKey(key) ? rootFile.get(key)
                    : entry.baseValue() != null ? entry.baseValue() : entry.codeDefault();
            assertEquals(SettingsCatalog.jsonValue(value), row.get("value"),
                    key + ": the root niord.json's value, else the base product's, else the code default");
        }

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"key\", source, dictionary, lang FROM public.\"DefaultsLedger\" "
                     + "WHERE kind = 'SETTING'")) {
            Set<String> ledgered = new TreeSet<>();
            while (rs.next()) {
                ledgered.add(rs.getString(1));
                assertTrue(Set.of("root", "base", "code").contains(rs.getString(2)),
                        rs.getString(1) + " is recorded as added, from a file or the code: " + rs.getString(2));
            }
            assertEquals(expected, ledgered, "one SETTING ledger row per added key");
        }

        // A key only the code declares, with no value in either settings file: the row has the code default, so the
        // administration app's maps start with the sea-chart layer switched off, as before the key was declared
        assertEquals(Map.of("value", false, "type", "Boolean", "web", true, "editable", true),
                Map.of("value", rows.get("wmsLayerEnabledByDefault").get("value"),
                        "type", rows.get("wmsLayerEnabledByDefault").get("type"),
                        "web", rows.get("wmsLayerEnabledByDefault").get("web"),
                        "editable", rows.get("wmsLayerEnabledByDefault").get("editable")),
                "start-up adds wmsLayerEnabledByDefault, off, served to the web applications and editable");

        // The generic boot (C5): the test root niord.json sets neither required key, so a fresh database gets the
        // base product's neutral country and organisation, recorded as from the base product
        assertEquals("XX", rows.get("country").get("value"), "the base product's neutral country");
        assertEquals("Niord", rows.get("organisation").get("value"), "the base product's neutral organisation");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"key\", source FROM public.\"DefaultsLedger\" "
                     + "WHERE kind = 'SETTING' AND \"key\" IN ('country', 'organisation')")) {
            Map<String, String> sources = new HashMap<>();
            while (rs.next()) {
                sources.put(rs.getString(1), rs.getString(2));
            }
            assertEquals(Map.of("country", "base", "organisation", "base"), sources, "their ledger source");
        }

        Integer addedAgain = new TransactionTemplate(transactionManager).execute(s -> settingsService.addMissing());
        assertEquals(0, addedAgain, "a second run adds nothing");
    }

    @Test
    void theSettingsFilesAuthServerUrlReachesTheRowAndTheInjectedField() throws Exception {
        assertEquals("http://localhost:8080/auth", rows().get("authServerUrl").get("value"),
                "the file's value, not the annotation default /auth");
        Object service = AopTestUtils.getUltimateTargetObject(keycloakIntegrationService);
        assertEquals("http://localhost:8080/auth", ReflectionTestUtils.getField(service, "authServerUrl"),
                "the field injected while the context started holds the file's value too");
    }

    @Test
    void theWebSettingsServeWhatBothFrontendsReadAtStartUpAndWriteNoRow() throws Exception {
        RestAssured.port = port;
        RestAssured.basePath = "";
        Map<String, Map<String, Object>> before = rows();
        var response = given().when().get("/rest/settings/web").then().statusCode(200);
        for (String key : new String[] {"mode", "osmUrl", "openSeaMapUrl", "mapDefaultZoomLevel",
                "mapDefaultLatitude", "mapDefaultLongitude", "wmsLayerEnabled", "wmsLayerEnabledByDefault",
                "openSeaMapLayerEnabled", "siteLanguages", "infoSiteLanguages", "defaultLanguage", "modelLanguages"}) {
            response.body("$", hasKey(key));
        }
        assertEquals(before, rows(), "the GET writes no Setting row");
    }

    // ------------------------------------------------------------------------------------------- //

    /** The Setting rows by key: value (as JSON value), type, description and flags */
    private Map<String, Map<String, Object>> rows() throws Exception {
        Map<String, Map<String, Object>> rows = new HashMap<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT setting_key, setting_value, type, description, cached, web, "
                     + "editable FROM public.\"Setting\"")) {
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                String json = rs.getString(2);
                row.put("value", json == null ? null : SettingsCatalog.jsonValue(
                        JsonMapper.builderWithJackson2Defaults().build().readValue(json, Object.class)));
                row.put("type", rs.getString(3));
                row.put("description", rs.getString(4));
                row.put("cached", rs.getBoolean(5));
                row.put("web", rs.getBoolean(6));
                row.put("editable", rs.getBoolean(7));
                rows.put(rs.getString(1), row);
            }
        }
        assertNotNull(rows.get("authServerUrl"), "start-up added authServerUrl");
        return rows;
    }

    /** The key and value of each entry of the test tree's root niord.json */
    private Map<String, Object> rootSettings() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("niord.json")) {
            JsonNode root = JsonMapper.builderWithJackson2Defaults().build().readTree(in);
            for (JsonNode entry : root.values()) {
                JsonNode value = entry.get("value");
                values.put(entry.get("key").asString(), value == null || value.isNull() ? null
                        : JsonMapper.builderWithJackson2Defaults().build().treeToValue(value, Object.class));
            }
        }
        return values;
    }
}
