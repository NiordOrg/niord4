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
package org.niord.core;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.niord.core.bootstrap.BootstrapArchive;
import org.niord.core.bootstrap.BootstrapRunner;
import org.niord.core.dictionary.DictionaryEntry;
import org.niord.core.dictionary.DictionaryService;
import org.niord.test.ITTest;
import org.niord.test.NiordTestApplication;
import org.niord.test.NiordTestContainers;
import org.niord.test.sample.SampleReferences;
import org.niord.test.sample.SampleSafetyCheck;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample on a country's base (docs/code-data-config-plan.md §8; docs/fe-country-split-plan.md §3.4):
 * the base product's sample loads on any country's backend after the base product's bootstrap. A country's first
 * start writes its own default texts before any bootstrap (DefaultTexts, whose result DictionaryService adds where
 * the database lacks it), so the base rows' ids differ from those of a base-only first start; the sample's own rows
 * take ids from {@link SampleReferences#FIRST_ID} on and name the base rows by natural key, and its guard checks that
 * range and those keys.
 *
 * <p>On a fresh PostGIS container whose database is named {@code niord_sample}, as the loader demands: the first
 * start of the test application (which writes the default texts, and whose settings name {@code da} among the model
 * languages), then what a country's first start adds besides, which the test adds itself (English keys the base
 * product lacks and Danish texts, written through JPA so the ids come from Hibernate's own sequences), the base
 * product's bootstrap in-process, and the sample's SQL run with {@code psql} inside the container, as the loader
 * runs it. The sample is there and wired to the base rows by key; a second run is refused by the offset check,
 * writing nothing; and the SQL's own name rule takes {@code niord_sample_<cc>} and refuses any other name.
 *
 * <p>Like {@code BootstrapITTest} it does not extend {@code ITTest}: it starts the application on a fresh, un-seeded
 * container with the bare master changelog. The methods run in order on that one database.
 */
@SpringBootTest(classes = NiordTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SampleOnACountryBaseITTest {

    private static final PostgreSQLContainer<?> FRESH_POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("imresamu/postgis:18-3.6")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("niord_sample")
                    .withUsername("niord")
                    .withPassword("niord");

    /** Where the sample's SQL is copied in the container */
    private static final String SQL_IN_CONTAINER = "/tmp/niord-sample.sql";

    /** The keys a country carries and the base product lacks: web lang.da and DK's agency */
    private static final Map<String, String> COUNTRY_KEYS = Map.of("lang.da", "web", "msg.responsible.agency", "message");

    static {
        // As BootstrapITTest: ITTest's static block (NIORD_HOME, twitter4j's restBaseURL), and niord.home
        try {
            Class.forName(ITTest.class.getName(), true, SampleOnACountryBaseITTest.class.getClassLoader());
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
        registry.add("spring.datasource.username", () -> freshPostgres().getUsername());
        registry.add("spring.datasource.password", () -> freshPostgres().getPassword());
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
    }

    @Autowired
    BootstrapRunner runner;

    @Autowired
    DictionaryService dictionaryService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @PersistenceContext
    EntityManager em;

    /** Per table the sample inserts into that has an id: its row count and largest id before the load */
    private static final Map<String, long[]> BEFORE = new TreeMap<>();

    // ------------------------------------------------------------------------------------------- //

    @Test
    @Order(1)
    void theFirstStartWroteTheDefaultTexts() {
        assertEquals(1, count("SELECT count(*) FROM \"DictionaryEntryDesc\" v "
                + "JOIN \"DictionaryEntry\" e ON e.id = v.entity_id JOIN \"Dictionary\" d ON d.id = e.dictionary_id "
                + "WHERE d.name = 'web' AND e.dict_key = 'lang.en' AND v.lang = 'en'"),
                "the first start writes the English text of defaults/base/texts/en/web.properties' lang.en");
        assertEquals(0, count("SELECT count(*) FROM \"Domain\""), "no bootstrap has run yet");
    }

    /** What a country's first start adds before any bootstrap: its own English keys and Danish texts */
    @Test
    @Order(2)
    void aCountrysFirstStartAddsItsOwnTexts() throws IOException {
        COUNTRY_KEYS.forEach((key, dictionary) -> {
            List<Integer> ids = jdbc.queryForList("SELECT e.id FROM \"DictionaryEntry\" e JOIN \"Dictionary\" d "
                    + "ON d.id = e.dictionary_id WHERE d.name = '" + dictionary + "' AND e.dict_key = '" + key + "'",
                    Integer.class);
            if (ids.isEmpty()) {
                DictionaryEntry entry = new DictionaryEntry();
                entry.setKey(key);
                entry.createDesc("en").setValue("A country's text: " + key);
                entry.createDesc("da").setValue("Et lands tekst: " + key);
                dictionaryService.createEntry(dictionary, entry);
            } else {
                // The key has a Danish text only (the stand-in da pack's lang.da, say): the country's English beside it
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    DictionaryEntry entry = em.find(DictionaryEntry.class, ids.getFirst());
                    if (entry.getDesc("en") == null) {
                        entry.createDesc("en").setValue("A country's text: " + key);
                    }
                });
            }
        });
        // A Danish text for a key the base product has, where the database has none yet (the test tree's stand-in
        // da pack, defaults/packs/da/, may have given every key one already)
        List<Integer> withoutDanish = jdbc.queryForList("SELECT e.id FROM \"DictionaryEntry\" e JOIN \"Dictionary\" d "
                + "ON d.id = e.dictionary_id WHERE NOT EXISTS (SELECT 1 FROM \"DictionaryEntryDesc\" v "
                + "WHERE v.entity_id = e.id AND v.lang = 'da') ORDER BY d.name, e.dict_key LIMIT 1", Integer.class);
        for (Integer id : withoutDanish) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                DictionaryEntry entry = em.find(DictionaryEntry.class, id);
                entry.createDesc("da").setValue("Et lands tekst: " + entry.getKey());
            });
            assertEquals(1, count("SELECT count(*) FROM \"DictionaryEntryDesc\" WHERE lang = 'da' AND entity_id = " + id));
        }
        assertTrue(count("SELECT count(*) FROM \"DictionaryEntryDesc\" WHERE lang = 'da'") > 0, "Danish texts");

        Set<String> base = baseEnglishKeys();
        List<String> english = jdbc.queryForList("SELECT d.name || ' ' || e.dict_key FROM \"DictionaryEntry\" e "
                + "JOIN \"Dictionary\" d ON d.id = e.dictionary_id JOIN \"DictionaryEntryDesc\" v ON v.entity_id = e.id "
                + "WHERE v.lang = 'en'", String.class);
        List<String> outside = english.stream().filter(k -> !base.contains(k)).toList();
        assertTrue(outside.size() >= 2, "at least two English keys outside defaults/base/texts/en: " + outside);
    }

    @Test
    @Order(3)
    void theBaseProductsBootstrapIsApplied() throws IOException {
        BootstrapArchive base = BootstrapArchive.read("niord-bootstrap.zip",
                new ByteArrayInputStream(SampleFolderTest.zipFolder(Path.of("bootstrap"))));
        BootstrapRunner.Outcome outcome = runner.run(List.of(base));
        assertTrue(outcome.applied(), "the base bootstrap: " + outcome);
    }

    @Test
    @Order(4)
    void theSampleLoads() throws Exception {
        for (String table : idTables()) {
            BEFORE.put(table, jdbc.queryForObject("SELECT count(*), coalesce(max(id), 0) FROM \"" + table + "\"",
                    (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}));
        }
        freshPostgres().copyFileToContainer(MountableFile.forHostPath(SampleFolderTest.SQL), SQL_IN_CONTAINER);
        Container.ExecResult result = psql("niord_sample");
        assertEquals(0, result.getExitCode(), "the sample's SQL on a country's base: " + result.getStderr());
    }

    @Test
    @Order(5)
    void theSampleIsThereAndNamesTheBaseRowsByKey() throws IOException {
        assertEquals(34, count("SELECT count(*) FROM \"MessageDesc\" WHERE lang = 'en' AND title LIKE 'SAMPLE "
                + "—%'"), "the sample's messages");
        assertEquals(1, count("SELECT count(*) FROM \"Domain\" WHERE \"domainId\" = 'niord-nw'"));

        // The sample's Danish name of the base category Light, on the base row
        Map<String, Object> light = jdbc.queryForMap("SELECT d.id, d.name, d.entity_id FROM \"CategoryDesc\" d "
                + "JOIN \"CategoryDesc\" e ON e.entity_id = d.entity_id AND e.lang = 'en' "
                + "WHERE e.name = 'Light' AND d.lang = 'da'");
        assertEquals("Fyr", light.get("name"));
        assertTrue(((Number) light.get("id")).longValue() >= SampleReferences.FIRST_ID, light.toString());
        assertTrue(((Number) light.get("entity_id")).longValue() < SampleReferences.FIRST_ID, light.toString());

        // A Danish text of the sample's on a base aton entry and on a base template parameter, as the SQL names them
        for (String table : List.of("DictionaryEntryDesc", "TemplateParamDesc")) {
            SampleSafetyCheck.Insert insert = SampleSafetyCheck.inserts(SampleFolderTest.read(SampleFolderTest.SQL))
                    .stream()
                    .filter(i -> i.table().equals(table) && "da".equals(i.text("lang"))
                            && SampleReferences.parse(value(i, "entity_id")) != null)
                    .findFirst().orElseThrow(() -> new AssertionError("the SQL names no base row from " + table));
            SampleReferences.Reference reference = SampleReferences.parse(value(insert, "entity_id"));
            Integer baseId = jdbc.queryForObject("SELECT " + reference.value(), Integer.class);
            assertNotNull(baseId, reference.text() + " resolves");
            assertTrue(baseId < SampleReferences.FIRST_ID, reference.text() + " is a base row");
            String column = "DictionaryEntryDesc".equals(table) ? "value" : "name";
            Map<String, Object> desc = jdbc.queryForMap("SELECT id, " + column + " AS text FROM \"" + table
                    + "\" WHERE lang = 'da' AND entity_id = ?", baseId);
            assertEquals(insert.text(column), desc.get("text"), table + " of " + reference.text());
            assertEquals(Long.parseLong(value(insert, "id")), ((Number) desc.get("id")).longValue(), desc.toString());
        }

        // Every row the load added is at the offset, and every sequence it touched hands out ids above them
        Map<String, Integer> inserts = new LinkedHashMap<>();
        SampleSafetyCheck.inserts(SampleFolderTest.read(SampleFolderTest.SQL))
                .forEach(i -> inserts.merge(i.table(), 1, Integer::sum));
        for (Map.Entry<String, long[]> e : BEFORE.entrySet()) {
            String table = e.getKey();
            long[] before = e.getValue();
            assertTrue(before[1] < SampleReferences.FIRST_ID, "\"" + table + "\" held no row at the offset");
            long[] after = jdbc.queryForObject("SELECT count(*), count(*) FILTER (WHERE id >= "
                    + SampleReferences.FIRST_ID + "), coalesce(max(id), 0) FROM \"" + table + "\"",
                    (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)});
            assertEquals(after[0] - before[0], after[1], "every row the sample added to \"" + table + "\" is at the offset");
            assertEquals((long) inserts.get(table), after[1], "the SQL's rows of \"" + table + "\"");
            if (count("SELECT count(*) FROM pg_class WHERE relname = '" + table + "_SEQ'") == 1) {
                long last = jdbc.queryForObject("SELECT last_value FROM \"" + table + "_SEQ\"", Long.class);
                assertTrue(last >= after[2], "\"" + table + "_SEQ\" at " + last + ", below the sample's " + after[2]);
            }
        }
    }

    @Test
    @Order(6)
    void aSecondLoadIsRefusedByTheOffsetCheck() throws Exception {
        long messages = count("SELECT count(*) FROM \"MessageDesc\"");
        Container.ExecResult result = psql("niord_sample");
        assertEquals(3, result.getExitCode(), "refused: " + result.getStderr());
        assertTrue(result.getStderr().contains("already holds a row, or its sequence is, where the sample's go (id >= "
                + SampleReferences.FIRST_ID + ")"), result.getStderr());
        assertEquals(messages, count("SELECT count(*) FROM \"MessageDesc\""), "no row written");
    }

    @Test
    @Order(7)
    void theSqlsOwnNameRuleTakesACountrysSampleDatabaseOnly() throws Exception {
        freshPostgres().copyFileToContainer(MountableFile.forHostPath(SampleFolderTest.SQL), SQL_IN_CONTAINER);
        for (String db : List.of("niord_sample_dk", "niord_dk")) {
            Container.ExecResult created = freshPostgres().execInContainer("createdb", "-U", "niord", db);
            assertEquals(0, created.getExitCode(), created.getStderr());
        }
        Container.ExecResult country = psql("niord_sample_dk");
        assertEquals(3, country.getExitCode(), country.getStderr());
        assertTrue(country.getStderr().contains("niord-sample: niord_sample_dk has no Niord schema"),
                "niord_sample_dk passes the name rule: " + country.getStderr());

        Container.ExecResult other = psql("niord_dk");
        assertEquals(3, other.getExitCode(), other.getStderr());
        assertTrue(other.getStderr().contains("niord-sample: the sample is loaded only into niord_sample or "
                + "niord_sample_<cc>, not niord_dk"), other.getStderr());
    }

    // ------------------------------------------------------------------------------------------- //

    /** The sample's SQL run with psql inside the container, as load-sample.sh runs it */
    private static Container.ExecResult psql(String db) throws IOException, InterruptedException {
        return freshPostgres().execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", "niord", "-d", db,
                "-f", SQL_IN_CONTAINER);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    /** The tables the SQL inserts into that have an id column */
    private Set<String> idTables() throws IOException {
        Set<String> tables = new HashSet<>();
        for (SampleSafetyCheck.Insert insert : SampleSafetyCheck.inserts(SampleFolderTest.read(SampleFolderTest.SQL))) {
            if (insert.columns().contains("id")) {
                tables.add(insert.table());
            }
        }
        return tables;
    }

    private static String value(SampleSafetyCheck.Insert insert, String column) {
        return insert.values().get(insert.columns().indexOf(column));
    }

    /** The base product's English keys, as {@code <dictionary> <key>}, from defaults/base/texts/en */
    private static Set<String> baseEnglishKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        for (String bundle : List.of("web", "message", "pdf", "mail", "template")) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(
                    Path.of("src/main/resources/defaults/base/texts/en/" + bundle + ".properties"))) {
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            properties.stringPropertyNames().forEach(k -> keys.add(bundle + " " + k));
        }
        return keys;
    }
}
