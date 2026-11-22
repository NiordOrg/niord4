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

import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.niord.core.batch.BatchData;
import org.niord.core.batch.BatchService;
import org.niord.core.report.FmReportService;
import org.niord.core.script.ScriptResource;
import org.niord.core.script.ScriptResourceImportService;
import org.niord.core.script.vo.ScriptResourceImportPreviewVo;
import org.niord.core.script.vo.ScriptResourceVo;
import org.niord.test.BatchJobs;
import org.niord.test.ITTest;
import org.niord.test.NiordTestApplication;
import org.niord.test.NiordTestContainers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first bootstrap's rule (docs/code-data-config-plan.md §7.2, C6, D4 as amended on 2026-10-24;
 * {@link BootstrapRun}): one run applies two test archives in order, {@code same-run-earlier} then
 * {@code same-run-later}, and for every entry the earlier archive created, each value the later archive gives replaces
 * the earlier one's, and a value the earlier archive left empty is filled: a template, a dictionary entry's value,
 * long value and AtoN filter, a category's flags, sort order, names, template parameters and lists, a composite
 * parameter type's template parameters, a list type's values, a promulgation type's fields and a report's fields and
 * properties. A value the later archive leaves out (absent, null or an empty string) keeps the earlier one's, and a
 * list it gives explicitly empty empties the earlier one's (the owner's decision of 2026-10-25); what is new (an
 * entry, a language, a template parameter, a domain link) is added; nothing else is removed, and domain links are a
 * union. A value equal to the row's writes nothing: an equal category keeps its version and its template parameter
 * rows. A repeated paramId is matched by its order, a domain listed twice is linked once, a new active child makes an
 * earlier archive's inactive category active, and a category the later archive makes inactive takes its active
 * descendants with it. A start-up default text the later archive restates keeps start-up's value. The job logs of
 * the later archive say what was added, replaced and kept, and name what the active flag carried through the tree
 * and the default text kept. After the run every sysadmin's import behaves as before: the preview and ticks of the
 * templates and
 * dictionaries, and the whole update of a category, a parameter type, a promulgation type and a report, also when
 * the job carries the ended run's token. The fixtures' READMEs name every deliberate entry.
 *
 * <p>A row from before the run does not occur: the claim refuses a database that holds data
 * ({@link BootstrapITTest}), so this class runs on a fresh database whose only rows are start-up's.
 *
 * <p>As {@link BootstrapITTest}: its own fresh, un-seeded PostGIS container, not {@code ITTest}'s seeded database. The
 * methods run in order on that one database, the first one the run.
 */
@SpringBootTest(classes = NiordTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BootstrapSameRunITTest {

    private static final PostgreSQLContainer<?> FRESH_POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("imresamu/postgis:18-3.6")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("niord")
                    .withUsername("niord")
                    .withPassword("niord");

    private static final String DICTIONARY = "it-same-run";

    /** How the job log names an entry the earlier archive of the run created */
    private static final String CREATED_IT = " (an earlier archive of this bootstrap run created it)";

    static {
        // As BootstrapITTest: NIORD_HOME and the rest of ITTest's static set-up, and niord.home for the fresh database
        try {
            Class.forName(ITTest.class.getName(), true, BootstrapSameRunITTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
        if (System.getProperty("niord.home") == null) {
            System.setProperty("niord.home", ITTest.homeVariable());
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
        registry.add("niord.schedule.auto-publish-cron", () -> "-");
        registry.add("niord.schedule.auto-expire-cron", () -> "-");
        registry.add("niord.schedule.firing-exercise-cron", () -> "-");
        registry.add("niord.mailinglist.status-change-sweep-cron", () -> "-");
    }

    @Autowired
    BootstrapMarker marker;

    @Autowired
    BootstrapRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @PersistenceContext
    EntityManager em;

    @Autowired
    ScriptResourceImportService scriptResourceImportService;

    @Autowired
    FmReportService reportService;

    @Autowired
    BatchService batchService;

    @Autowired
    JobOperator jobOperator;

    private static BootstrapArchive fixture(String name) {
        return BootstrapArchiveTest.read(name + ".zip",
                BootstrapArchiveTest.zipFolder(BootstrapArchiveTest.FIXTURES.resolve(name)));
    }

    // ------------------------------------------------------------------------------------------- //
    // Reading the database
    // ------------------------------------------------------------------------------------------- //

    private String content(String path) {
        List<String> rows = jdbc.queryForList("SELECT content FROM public.\"ScriptResource\" WHERE path = ?",
                String.class, path);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<String> historySources(String path) {
        return jdbc.queryForList("SELECT coalesce(h.source, 'none') FROM public.\"ScriptResourceHistory\" h "
                + "JOIN public.\"ScriptResource\" r ON h.resource_id = r.id WHERE r.path = ? ORDER BY h.id",
                String.class, path);
    }

    /** The values of a key of the test dictionary by language: value, then '|' and the long value when it has one */
    private Map<String, String> entry(String key) {
        Map<String, String> values = new HashMap<>();
        jdbc.query("SELECT d.lang, d.value, d.\"longValue\" FROM public.\"DictionaryEntryDesc\" d "
                        + "JOIN public.\"DictionaryEntry\" e ON d.entity_id = e.id "
                        + "JOIN public.\"Dictionary\" x ON e.dictionary_id = x.id WHERE x.name = ? AND e.dict_key = ?",
                rs -> {
                    String longValue = rs.getString(3);
                    values.put(rs.getString(1), rs.getString(2) + (longValue != null ? "|" + longValue : ""));
                }, DICTIONARY, key);
        return values;
    }

    private int category(String englishName) {
        return jdbc.queryForObject("SELECT entity_id FROM public.\"CategoryDesc\" WHERE lang = 'en' AND name = ?",
                Integer.class, englishName);
    }

    private Map<String, String> categoryDescs(int category) {
        Map<String, String> names = new HashMap<>();
        jdbc.query("SELECT lang, name FROM public.\"CategoryDesc\" WHERE entity_id = ?",
                rs -> {
                    names.put(rs.getString(1), rs.getString(2));
                }, category);
        return names;
    }

    private List<String> categoryDomains(int category) {
        return jdbc.queryForList("SELECT d.\"domainId\" FROM public.\"Category_Domain\" c "
                + "JOIN public.\"Domain\" d ON c.domains_id = d.id WHERE c.\"Category_id\" = ? ORDER BY d.\"domainId\"",
                String.class, category);
    }

    /** The template parameters of a category, by paramId in order, each with its names by language */
    private Map<String, Map<String, String>> categoryParams(int category) {
        return params("SELECT t.id, t.\"paramId\" FROM public.\"Category_TemplateParam\" c "
                + "JOIN public.\"TemplateParam\" t ON c.\"templateParams_id\" = t.id WHERE c.\"Category_id\" = ? "
                + "ORDER BY c.\"indexNo\"", category);
    }

    /** The template parameters of a composite parameter type, by paramId in order, with their names by language */
    private Map<String, Map<String, String>> typeParams(String name) {
        return params("SELECT t.id, t.\"paramId\" FROM public.\"ParamType_TemplateParam\" c "
                + "JOIN public.\"TemplateParam\" t ON c.\"templateParams_id\" = t.id "
                + "JOIN public.\"ParamType\" p ON c.\"CompositeParamType_id\" = p.id WHERE p.name = ? "
                + "ORDER BY c.\"indexNo\"", name);
    }

    private Map<String, Map<String, String>> params(String sql, Object arg) {
        Map<String, Map<String, String>> params = new LinkedHashMap<>();
        paramList(sql, arg).forEach(p -> params.put(p.paramId(), p.names()));
        return params;
    }

    /** A template parameter as stored: its paramId and its names by language */
    private record Param(String paramId, Map<String, String> names) {
    }

    /** The template parameters of a composite parameter type in their order, a paramId that repeats included */
    private List<Param> typeParamList(String name) {
        return paramList("SELECT t.id, t.\"paramId\" FROM public.\"ParamType_TemplateParam\" c "
                + "JOIN public.\"TemplateParam\" t ON c.\"templateParams_id\" = t.id "
                + "JOIN public.\"ParamType\" p ON c.\"CompositeParamType_id\" = p.id WHERE p.name = ? "
                + "ORDER BY c.\"indexNo\"", name);
    }

    private List<Param> paramList(String sql, Object arg) {
        List<Param> params = new ArrayList<>();
        jdbc.query(sql, rs -> {
            Map<String, String> names = new HashMap<>();
            jdbc.query("SELECT lang, name FROM public.\"TemplateParamDesc\" WHERE entity_id = ?",
                    r -> {
                        names.put(r.getString(1), r.getString(2));
                    }, rs.getInt(1));
            params.add(new Param(rs.getString(2), names));
        }, arg);
        return params;
    }

    /** The values of a list parameter type, by key in order */
    private List<String> listValues(String name) {
        return jdbc.queryForList("SELECT e.dict_key FROM public.\"ParamType_DictionaryEntry\" v "
                + "JOIN public.\"DictionaryEntry\" e ON v.values_id = e.id "
                + "JOIN public.\"ParamType\" p ON v.\"ListParamType_id\" = p.id WHERE p.name = ? "
                + "ORDER BY v.\"indexNo\"", String.class, name);
    }

    private List<String> categoryScriptPaths(int category) {
        return jdbc.queryForList("SELECT \"scriptResourcePaths\" FROM public.\"Category_scriptResourcePaths\" "
                + "WHERE \"Category_id\" = ? ORDER BY \"indexNo\"", String.class, category);
    }

    private List<String> domainsOf(String table, String idColumn, String keyColumn, String key) {
        return jdbc.queryForList("SELECT d.\"domainId\" FROM public.\"" + table + "_Domain\" l "
                + "JOIN public.\"Domain\" d ON l.domains_id = d.id "
                + "JOIN public.\"" + table + "\" t ON l.\"" + idColumn + "\" = t.id WHERE t.\"" + keyColumn + "\" = ? "
                + "ORDER BY d.\"domainId\"", String.class, key);
    }

    // ------------------------------------------------------------------------------------------- //
    // Starting a job as a sysadmin's import does
    // ------------------------------------------------------------------------------------------- //

    /** Runs a job on JSON data and returns what it wrote to its log */
    private String runJob(String job, Object data, Map<String, Object> properties) throws Exception {
        Map<String, Long> before = logLineCounts(List.of(job));
        long execution = batchService.startBatchJobWithJsonData(job, data, job + "-" + System.nanoTime() + ".json",
                properties);
        assertEquals(BatchStatus.COMPLETED, BatchJobs.awaitCompletion(jobOperator, execution), job);
        return logSince(jobOperator.getJobInstance(execution).getInstanceId(), before);
    }

    /**
     * The line count of every log file the jobs have, by path. A job's folder is named by its job number, which starts
     * again in each fresh database, and NIORD_HOME is shared, so a folder may hold the logs of earlier test runs: a
     * job's own lines are those after these counts.
     */
    private Map<String, Long> logLineCounts(List<String> jobs) throws IOException {
        Map<String, Long> counts = new HashMap<>();
        for (String job : jobs) {
            Path folder = batchService.getBatchJobRoot().resolve(job);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith("Log.txt")).toList()) {
                    long lines = 0;
                    for (byte b : Files.readAllBytes(file)) {
                        lines += b == '\n' ? 1 : 0;
                    }
                    counts.put(file.toAbsolutePath().normalize().toString(), lines);
                }
            }
        }
        return counts;
    }

    /** What a job instance wrote to its log files after the counts were taken */
    private String logSince(long instance, Map<String, Long> before) throws IOException {
        BatchData job = batchService.findByInstanceId(instance);
        Path folder = batchService.computeBatchJobPath(job.computeBatchJobFolderPath());
        StringBuilder log = new StringBuilder();
        for (String file : batchService.getBatchJobLogFiles(instance)) {
            long from = before.getOrDefault(folder.resolve(file).toAbsolutePath().normalize().toString(), 0L);
            log.append(batchService.getBatchJobLogFile(instance, file, (int) from)).append('\n');
        }
        return log.toString();
    }

    /** The jobs of a bootstrap run, whose logs the run's job-log test reads */
    private static final List<String> RUN_JOBS = List.of("script-resource-import", "dictionary-import",
            "category-import", "param-type-import", "report-import", "promulgation-type-import");

    /** The line counts of the run's jobs' log files, taken before the run */
    private static Map<String, Long> logsBeforeTheRun;

    private static List<Map<String, Object>> dictionary(Map<String, Object> entry) {
        return List.of(Map.of("name", DICTIONARY, "entries", List.of(entry)));
    }

    private static Map<String, Object> value(String lang, String value) {
        return Map.of("lang", lang, "value", value);
    }

    private static Map<String, Object> desc(String lang, String name) {
        return Map.of("lang", lang, "name", name);
    }

    /** A category's row version, which every write of the row moves on */
    private int version(int category) {
        return jdbc.queryForObject("SELECT version FROM public.\"Category\" WHERE id = ?", Integer.class, category);
    }

    /** The row ids of a category's template parameters, by paramId */
    private Map<String, Integer> categoryParamIds(int category) {
        Map<String, Integer> ids = new LinkedHashMap<>();
        jdbc.query("SELECT t.\"paramId\", t.id FROM public.\"Category_TemplateParam\" c "
                + "JOIN public.\"TemplateParam\" t ON c.\"templateParams_id\" = t.id WHERE c.\"Category_id\" = ? "
                + "ORDER BY c.\"indexNo\"", rs -> {
                    ids.put(rs.getString(1), rs.getInt(2));
                }, category);
        return ids;
    }

    /** A flag of a category's template parameter: mandatory or positionList */
    private boolean categoryParamFlag(int category, String paramId, String column) {
        return jdbc.queryForObject("SELECT t.\"" + column + "\" FROM public.\"Category_TemplateParam\" c "
                + "JOIN public.\"TemplateParam\" t ON c.\"templateParams_id\" = t.id "
                + "WHERE c.\"Category_id\" = ? AND t.\"paramId\" = ?", Boolean.class, category, paramId);
    }

    /** A column of a promulgation type's row */
    private Object promulgationType(String typeId, String column) {
        return jdbc.queryForObject("SELECT \"" + column + "\" FROM public.\"PromulgationType\" WHERE \"typeId\" = ?",
                Object.class, typeId);
    }

    /** A promulgation type's message types, sorted, or its script paths in order */
    private List<String> promulgationList(String typeId, String list) {
        return jdbc.queryForList("SELECT l.\"" + list + "\" FROM public.\"PromulgationType_" + list + "\" l "
                + "JOIN public.\"PromulgationType\" t ON l.\"PromulgationType_id\" = t.id WHERE t.\"typeId\" = ? "
                + "ORDER BY " + ("scriptResourcePaths".equals(list) ? "l.\"indexNo\"" : "1"), String.class, typeId);
    }

    /** A column of a category's row */
    private Object categoryColumn(int category, String column) {
        return jdbc.queryForObject("SELECT \"" + column + "\" FROM public.\"Category\" WHERE id = ?", Object.class,
                category);
    }

    /** A category's editor fields or standard fields, sorted (the collections keep no order) */
    private List<String> categoryFields(int category, String list) {
        return jdbc.queryForList("SELECT \"" + list + "\" FROM public.\"Category_" + list + "\" "
                + "WHERE \"Category_id\" = ? ORDER BY 1", String.class, category);
    }

    private Map<String, Object> reportProperties(String reportId) {
        return reportService.findByReportId(reportId).getProperties();
    }

    // ------------------------------------------------------------------------------------------- //

    /** The run: the earlier archive, then the later one, on a database that holds only start-up's rows */
    @Test
    @Order(1)
    void oneRunAppliesTheEarlierArchiveThenTheLaterOne() throws Exception {
        assertEquals(BootstrapMarker.PENDING, marker.read().map(BootstrapMarker.State::state).orElse(null));

        logsBeforeTheRun = logLineCounts(RUN_JOBS);
        BootstrapRunner.Outcome outcome = runner.run(List.of(fixture("same-run-earlier"), fixture("same-run-later")));

        assertTrue(outcome.applied(), String.valueOf(outcome.error()));
    }

    /** (a) A template the earlier archive created is replaced; an equal one is left; a new one is added */
    @Test
    @Order(2)
    void aTemplateTheEarlierArchiveCreatedIsReplaced() {
        assertEquals("<#-- same-run-a: the later archive's -->\n", content("templates/it/same-run-a.ftl"));
        assertEquals(List.of("import", "import"), historySources("templates/it/same-run-a.ftl"),
                "created and replaced, both recorded as the import's");
        assertEquals("<#-- same-run-b: both archives' -->\n", content("templates/it/same-run-b.ftl"));
        assertEquals(1, historySources("templates/it/same-run-b.ftl").size(), "an equal template is not written");
        assertEquals("<#-- same-run-c: the later archive's -->\n", content("templates/it/same-run-c.ftl"));
    }

    /** (b) The later archive's languages and template parameters are added to what the earlier archive created */
    @Test
    @Order(3)
    void aLanguageIsAddedToWhatTheEarlierArchiveCreated() {
        assertEquals("en|den ene", entry("k1").get("da"), "k1 gained da");
        assertEquals(Map.of("en", "two", "da", "TO"), entry("k2"), "k2 kept its en, and its da was replaced");
        assertEquals(Map.of("en", "three"), entry("k3"), "k3 added");

        int root = category("It root");
        int child = category("It child");
        assertEquals("It rod", categoryDescs(root).get("da"));
        assertEquals("It barn", categoryDescs(child).get("da"));
        assertEquals("It parameter da", categoryParams(child).get("it_p").get("da"), "it_p gained da");

        // Among equal paramIds by their order: the file's second p1 gives its da to the type's second p1, and the
        // new p3 is appended
        List<Param> composite = typeParamList("it_composite");
        assertEquals(List.of("p1", "p2", "p1", "p3"), composite.stream().map(Param::paramId).toList());
        assertEquals("Første", composite.get(0).names().get("da"), "the first p1 gained da");
        assertEquals("Første igen", composite.get(2).names().get("da"), "the second p1 gained its own da");
        assertEquals(Map.of("en", "Third", "da", "Tredje"), composite.get(3).names(), "p3 added");
        assertEquals("Obligatorisk", typeParamList("it_mandatory").get(0).names().get("da"), "m1 gained da");
    }

    /** Within the run, a value the later archive gives replaces the earlier archive's; one it leaves out is kept */
    @Test
    @Order(4)
    void aValueTheLaterArchiveGivesReplacesTheEarlierOne() {
        // Each on its own: dictionary entries, a composite type's template parameter, a category's template
        // parameter and a category's names
        assertAll(
                () -> assertEquals("ONE|the ONE", entry("k1").get("en"), "k1's en and its long value"),
                () -> assertEquals("TO", entry("k2").get("da"), "k2's da"),
                () -> assertEquals("SEVEN|the seven", entry("k7").get("en"),
                        "k7's value; the long value the later archive leaves out is kept"),
                () -> assertEquals("it-filter-7", atonFilter("k7"), "the AtoN filter it leaves out is kept"),
                () -> assertEquals("f2", atonFilter("k8"), "an AtoN filter given with another value"),
                () -> assertEquals("it-filter", atonFilter("k5"), "k5's AtoN filter, left out, is kept"),
                () -> assertEquals(Map.of("en", "nine"), entry("k9"),
                        "an empty value counts as left out: k9's en is kept, and no empty da is added"),
                () -> assertEquals("AtoNs", webEnglish("menu.atons"),
                        "a start-up default text the later archive restates keeps start-up's value"),
                () -> assertEquals("FIRST", typeParamList("it_composite").get(0).names().get("en"), "p1's en"),
                () -> assertEquals("First again", typeParamList("it_composite").get(2).names().get("en")),
                () -> assertEquals("It PARAMETER", categoryParams(category("It child")).get("it_p").get("en"),
                        "it_p's en"),
                () -> assertEquals("It parameter da", categoryParams(category("It child")).get("it_p").get("da")),
                () -> assertEquals(Map.of("en", "It root", "fr", "RACINE", "da", "It rod", "de", "Wurzel"),
                        categoryDescs(category("It root")),
                        "the root's fr replaced, its da added, its de, which the later archive leaves out, kept"));
    }

    /** The English value of a key of the start-up's web texts */
    private String webEnglish(String key) {
        return jdbc.queryForObject("SELECT d.value FROM public.\"DictionaryEntryDesc\" d "
                + "JOIN public.\"DictionaryEntry\" e ON d.entity_id = e.id "
                + "JOIN public.\"Dictionary\" x ON e.dictionary_id = x.id "
                + "WHERE x.name = 'web' AND e.dict_key = ? AND d.lang = 'en'", String.class, key);
    }

    private String atonFilter(String key) {
        return jdbc.queryForObject("SELECT e.\"atonFilter\" FROM public.\"DictionaryEntry\" e "
                + "JOIN public.\"Dictionary\" x ON e.dictionary_id = x.id WHERE x.name = ? AND e.dict_key = ?",
                String.class, DICTIONARY, key);
    }

    /** (c) The later archive's domain links are added to what the earlier archive created, the earlier ones kept */
    @Test
    @Order(5)
    void aDomainLinkIsAddedToWhatTheEarlierArchiveCreated() {
        assertEquals(List.of("it-earlier", "it-later"), categoryDomains(category("It root")));
        // The later archive lists it-later twice, and it-missing, which no row carries: one link each, none for it
        assertEquals(List.of("it-earlier", "it-later"),
                domainsOf("PromulgationType", "PromulgationType_id", "typeId", "it-navtex"));
        assertEquals(List.of("it-earlier", "it-later"), domainsOf("FmReport", "FmReport_id", "reportId", "it-report"));
        // The later archive's child names no domain: its link is kept
        assertEquals(List.of("it-earlier"), categoryDomains(category("It child")));
    }

    /**
     * The later archive's scalars, flags and lists replace the earlier archive's; a value it leaves out is kept, an
     * equal one writes nothing, and no template parameter is removed
     */
    @Test
    @Order(6)
    void theLaterArchivesFieldsReplaceAndNothingIsRemoved() {
        int child = category("It child");
        int flags = category("It flags");
        assertAll(
                () -> assertEquals(5, promulgationType("it-navtex", "priority"), "it-navtex's priority"),
                () -> assertEquals("It report renamed", jdbc.queryForObject("SELECT name FROM public.\"FmReport\" "
                        + "WHERE \"reportId\" = 'it-report'", String.class), "it-report's name"),
                () -> assertEquals(Map.of("a", "1", "b", "3"), reportProperties("it-report"),
                        "it-report's properties, merged by key: b replaced, a kept"),
                () -> assertEquals(7.0, jdbc.queryForObject("SELECT \"siblingSortOrder\" FROM public.\"Category\" "
                        + "WHERE id = ?", Double.class, category("It root")), "the root's sort order"),
                () -> assertEquals(List.of("it_p", "it_q"), List.copyOf(categoryParams(child).keySet()),
                        "it_q appended to the child"),
                () -> assertEquals(List.of("templates/it/same-run-c.ftl"), categoryScriptPaths(child),
                        "the child's script paths"),
                () -> assertEquals(List.of("p1", "p2", "p1", "p3"),
                        typeParamList("it_composite").stream().map(Param::paramId).toList(),
                        "p2, which the later archive lacks, kept, and p3 appended"),
                () -> assertEquals(Map.of("en", "Second"), typeParamList("it_composite").get(1).names()),
                () -> assertEquals(List.of("k1", "k2"), listValues("it_list"), "a list type's values"),
                () -> assertEquals(List.of("s1"), typeParamList("it_same").stream().map(Param::paramId).toList()),
                // A template parameter's flags: left out kept, given false replaced
                () -> assertTrue(categoryParamFlag(flags, "it_m", "mandatory"), "it_m's mandatory, left out"),
                () -> assertTrue(categoryParamFlag(flags, "it_m", "positionList"), "it_m's position list, left out"),
                () -> assertFalse(categoryParamFlag(flags, "it_n", "positionList"),
                        "it_n's position list, given false"),
                () -> assertEquals("text", jdbc.queryForObject("SELECT t.type FROM public.\"Category_TemplateParam\" c "
                        + "JOIN public.\"TemplateParam\" t ON c.\"templateParams_id\" = t.id "
                        + "WHERE c.\"Category_id\" = ? AND t.\"paramId\" = 'it_m'", String.class, flags),
                        "it_m's type, left out"),
                () -> assertEquals(4.0, jdbc.queryForObject("SELECT \"siblingSortOrder\" FROM public.\"Category\" "
                        + "WHERE id = ?", Double.class, flags), "It flags' sort order, left out"),
                () -> assertEquals("TEMPLATE", jdbc.queryForObject("SELECT type FROM public.\"Category\" WHERE id = ?",
                        String.class, flags)),
                () -> assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT active FROM public.\"Category\" "
                        + "WHERE id = ?", Boolean.class, category("It deactivated")), "active given false"),
                // A composite type's mandatory parameter the later archive restates with names only
                () -> assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT t.mandatory "
                        + "FROM public.\"ParamType_TemplateParam\" c JOIN public.\"TemplateParam\" t "
                        + "ON c.\"templateParams_id\" = t.id JOIN public.\"ParamType\" p "
                        + "ON c.\"CompositeParamType_id\" = p.id WHERE p.name = 'it_mandatory'", Boolean.class),
                        "m1 stays mandatory"),
                () -> assertEquals("text", jdbc.queryForObject("SELECT t.type "
                        + "FROM public.\"ParamType_TemplateParam\" c JOIN public.\"TemplateParam\" t "
                        + "ON c.\"templateParams_id\" = t.id JOIN public.\"ParamType\" p "
                        + "ON c.\"CompositeParamType_id\" = p.id WHERE p.name = 'it_mandatory'", String.class),
                        "m1 keeps its type"),
                // A promulgation type of a typeId and domains only keeps every value
                () -> assertEquals("It domains only", promulgationType("it-domains-only", "name")),
                () -> assertEquals("navtex", promulgationType("it-domains-only", "serviceId")),
                () -> assertEquals(4, promulgationType("it-domains-only", "priority")),
                () -> assertEquals(Boolean.TRUE, promulgationType("it-domains-only", "active")),
                () -> assertEquals("en", promulgationType("it-domains-only", "language")),
                () -> assertEquals(List.of("it-later"),
                        domainsOf("PromulgationType", "PromulgationType_id", "typeId", "it-domains-only")),
                // One giving active false and priority 0 explicitly, and a requirement the earlier one left empty
                () -> assertEquals(Boolean.FALSE, promulgationType("it-flags", "active")),
                () -> assertEquals(0, promulgationType("it-flags", "priority")),
                () -> assertEquals("MANDATORY", promulgationType("it-flags", "requirement")),
                () -> assertEquals("It flags", promulgationType("it-flags", "name"), "the name, left out, kept"),
                // One whose name, service, language, requirement and lists the later archive replaces
                () -> assertEquals("It changes renamed", promulgationType("it-changes", "name")),
                () -> assertEquals("twitter", promulgationType("it-changes", "serviceId")),
                () -> assertEquals("da", promulgationType("it-changes", "language")),
                () -> assertEquals("MANDATORY", promulgationType("it-changes", "requirement"),
                        "from one value to another"),
                () -> assertEquals(2, promulgationType("it-changes", "priority"), "the priority, left out, kept"),
                () -> assertEquals(Boolean.TRUE, promulgationType("it-changes", "active"), "the flag, left out, kept"),
                () -> assertEquals(List.of("COASTAL_WARNING", "NAVAREA_WARNING"),
                        promulgationList("it-changes", "messageTypes")),
                () -> assertEquals(List.of("templates/it/same-run-b.ftl"),
                        promulgationList("it-changes", "scriptResourcePaths")),
                // Explicit empty lists empty the earlier archive's (the owner's decision of 2026-10-25)
                () -> assertEquals(List.of(), promulgationList("it-emptied", "messageTypes"), "emptied"),
                () -> assertEquals(List.of(), promulgationList("it-emptied", "scriptResourcePaths"), "emptied"),
                () -> assertEquals("It emptied", promulgationType("it-emptied", "name"), "the name, left out, kept"),
                // A category whose type, AtoN filter and lists are replaced and whose MRN is filled
                () -> assertEquals("TEMPLATE", categoryColumn(category("It changes"), "type")),
                () -> assertEquals("it-filter-b", categoryColumn(category("It changes"), "atonFilter")),
                () -> assertEquals("urn:mrn:iho:category:xx:it-changes", categoryColumn(category("It changes"), "mrn"),
                        "an empty MRN filled"),
                () -> assertEquals(List.of("it-b", "it-c"), categoryFields(category("It changes"), "editorFields")),
                () -> assertEquals(List.of("it-y"), categoryFields(category("It changes"), "stdTemplateFields")),
                () -> assertEquals("urn:mrn:iho:category:xx:it-mrn-a", categoryColumn(category("It mrn"), "mrn"),
                        "another MRN, a matching key, kept"),
                () -> assertEquals(List.of(), categoryFields(category("It emptied"), "editorFields"),
                        "an explicit empty list empties"),
                () -> assertEquals(List.of("it-x"), categoryFields(category("It emptied"), "stdTemplateFields"),
                        "a list left out is kept"),
                () -> assertEquals(Boolean.FALSE, categoryColumn(category("It inactive kept"), "active"),
                        "an active flag left out keeps an inactive category inactive"),
                // Made inactive, a category takes its active child with it (TreeBaseEntity.updateActiveFlag)
                () -> assertEquals(Boolean.FALSE, categoryColumn(category("It parent"), "active")),
                () -> assertEquals(Boolean.FALSE, categoryColumn(category("It active child"), "active"),
                        "the child the later archive does not name, made inactive with its parent"),
                // Made active, a category takes its inactive parent with it (TreeBaseEntity.updateActiveFlag)
                () -> assertEquals(Boolean.TRUE, categoryColumn(category("It activated child"), "active"),
                        "active given true"),
                () -> assertEquals(Boolean.TRUE, categoryColumn(category("It inactive parent"), "active"),
                        "the parent whose active flag the later archive leaves out, made active with its child"),
                // A report whose sort order and template path are replaced and which gains a parameter
                () -> assertEquals(2, jdbc.queryForObject("SELECT \"sortOrder\" FROM public.\"FmReport\" "
                        + "WHERE \"reportId\" = 'it-report-changes'", Integer.class)),
                () -> assertEquals("templates/it/same-run-b.ftl", reportService.findByReportId("it-report-changes")
                        .getTemplatePath()),
                () -> assertEquals(Map.of("p", "1", "q", "2"), reportService.findByReportId("it-report-changes")
                        .getParams(), "q added, p equal"),
                () -> assertEquals("It report changes", reportService.findByReportId("it-report-changes").getName(),
                        "the name, left out, kept"),
                // A composite type's template parameter type, and a list type's values emptied
                () -> assertEquals("number", jdbc.queryForObject("SELECT t.type "
                        + "FROM public.\"ParamType_TemplateParam\" c JOIN public.\"TemplateParam\" t "
                        + "ON c.\"templateParams_id\" = t.id JOIN public.\"ParamType\" p "
                        + "ON c.\"CompositeParamType_id\" = p.id WHERE p.name = 'it_typed'", String.class)),
                () -> assertEquals(List.of(), listValues("it_list_emptied"), "an explicit empty list empties"));

        // An entry equal in every value writes nothing: It equal has the version of its twin It earlier only, which
        // the later archive does not name, and its template parameter is the row the earlier archive wrote, older
        // than the later archive's first new one (the ids come from one sequence)
        int equal = category("It equal");
        assertEquals(version(category("It earlier only")), version(equal), "It equal was not written");
        assertTrue(categoryParamIds(equal).get("it_e") < categoryParamIds(child).get("it_q"),
                "It equal's template parameter row was not recreated: " + categoryParamIds(equal) + " "
                        + categoryParamIds(child));

        // A new active category makes its inactive parent active (TreeBaseEntity.updateActiveFlag), and the job log
        // says so
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT active FROM public.\"Category\" WHERE id = ?",
                Boolean.class, category("It inactive")), "It inactive was made active by It new child");
    }

    /** Every job of the run carried the runner's token: the run's id and the archive's index */
    @Test
    @Order(8)
    void everyJobOfTheRunCarriedTheRunnersToken() {
        List<BatchData> jobs = em.createQuery("select b from BatchData b where b.jobName = 'dictionary-import' "
                + "order by b.id", BatchData.class).getResultList();
        List<String> tokens = jobs.stream()
                .map(b -> b.getProperties().get(BootstrapRun.PROPERTY))
                .filter(t -> t != null)
                .map(String::valueOf)
                .toList();
        assertEquals(2, tokens.size(), "the run's two dictionary jobs: " + tokens);
        assertTrue(tokens.get(0).endsWith("/0") && tokens.get(1).endsWith("/1"), tokens.toString());
        assertEquals(tokens.get(0).replace("/0", ""), tokens.get(1).replace("/1", ""), "one run");
    }

    /**
     * After the run a sysadmin's import is unchanged: preview and ticks, no language added, and a category, a
     * parameter type, a promulgation type and a report updated whole, a list or a link they leave out cleared
     */
    @Test
    @Order(9)
    void aSysadminsImportAfterTheRunIsUnchanged() throws Exception {
        ScriptResourceVo changed = new ScriptResourceVo();
        changed.setType(ScriptResource.Type.FM);
        changed.setPath("templates/it/same-run-b.ftl");
        changed.setContent("<#-- same-run-b: a release's -->\n");

        ScriptResourceImportPreviewVo preview = scriptResourceImportService.preview(List.of(changed));
        assertEquals(List.of("templates/it/same-run-b.ftl"),
                preview.getOverwrite().stream().map(ScriptResourceImportPreviewVo.Overwrite::getPath).toList(),
                "the preview lists the overwrite");

        String log = runJob("script-resource-import", List.of(changed), Map.of());
        assertTrue(log.contains("Added 0, overwritten 0, kept 1, not added 0, unchanged 0"), log);
        assertEquals("<#-- same-run-b: both archives' -->\n", content("templates/it/same-run-b.ftl"),
                "without a tick the row is kept");

        String dictionaryLog = runJob("dictionary-import", dictionary(Map.of("key", "k1", "descs",
                List.of(value("en", "one"), value("kl", "ataaseq")))), Map.of());
        assertFalse(entry("k1").containsKey("kl"), "no language is added outside a run");
        assertTrue(dictionaryLog.contains("Dictionary it-same-run: added 0, overwritten 0, kept 1 (not ticked), "
                + "not added 0 (not ticked), unchanged 0"), dictionaryLog);
        assertFalse(dictionaryLog.contains("languages added to"), "outside a run the line is as before: "
                + dictionaryLog);

        // The whole update: the child restated without script paths, under its root
        String categoryLog = runJob("category-import", List.of(Map.of(
                "descs", List.of(desc("en", "It root")),
                "children", List.of(Map.of("descs", List.of(desc("en", "It child")), "type", "TEMPLATE",
                        "templateParams", List.of(Map.of("paramId", "it_p", "type", "text",
                                "descs", List.of(desc("en", "It PARAMETER")))))))), Map.of());
        assertEquals(List.of(), categoryScriptPaths(category("It child")), "the script paths it leaves out cleared");
        assertEquals(List.of("it_p"), List.copyOf(categoryParams(category("It child")).keySet()), "it_q removed");

        String typeLog = runJob("param-type-import", List.of(Map.of("type", "COMPOSITE", "name", "it_composite",
                "templateParams", List.of(Map.of("paramId", "p1", "type", "text",
                        "descs", List.of(desc("en", "FIRST")))))), Map.of());
        assertEquals(List.of("p1"), typeParamList("it_composite").stream().map(Param::paramId).toList(),
                "the type's template parameters replaced whole");

        String promulgationLog = runJob("promulgation-type-import", List.of(Map.of("typeId", "it-navtex",
                "serviceId", "navtex", "name", "It NAVTEX", "priority", 5, "active", true, "language", "en")),
                Map.of());
        assertEquals(List.of(), domainsOf("PromulgationType", "PromulgationType_id", "typeId", "it-navtex"),
                "the domains it leaves out cleared");

        String reportLog = runJob("report-import", List.of(Map.of("reportId", "it-report", "name",
                "It report renamed", "sortOrder", 0, "templatePath", "templates/it/same-run-a.ftl",
                "properties", Map.of("a", "1"))), Map.of());
        assertEquals(List.of(), domainsOf("FmReport", "FmReport_id", "reportId", "it-report"),
                "the domains it leaves out cleared");
        assertEquals(Map.of("a", "1"), reportProperties("it-report"), "the properties replaced whole");

        for (String jobLog : List.of(categoryLog, typeLog, promulgationLog, reportLog)) {
            assertFalse(jobLog.contains(CREATED_IT), "no line of the first bootstrap's rule: " + jobLog);
        }
    }

    /**
     * A job that carries the token of the run that has ended applies no rule: the later archive's token, on entries the
     * earlier archive created, which the rule would add to and replace while the run was live
     */
    @Test
    @Order(10)
    void theTokenOfTheEndedRunIsInert() throws Exception {
        Object token = em.createQuery("select b from BatchData b where b.jobName = 'dictionary-import' "
                        + "order by b.id", BatchData.class).getResultList().stream()
                .map(b -> b.getProperties().get(BootstrapRun.PROPERTY))
                .filter(t -> t != null && String.valueOf(t).endsWith("/1"))
                .findFirst()
                .orElse(null);
        assertNotNull(token, "the later archive's dictionary job carried the token");
        Map<String, Object> ended = Map.of(BootstrapRun.PROPERTY, token);

        runJob("dictionary-import", dictionary(Map.of("key", "k1", "descs",
                List.of(value("en", "one"), value("kl", "ataaseq")))), ended);
        assertFalse(entry("k1").containsKey("kl"), "the ended run's token adds no language");

        ScriptResourceVo changed = new ScriptResourceVo();
        changed.setType(ScriptResource.Type.FM);
        changed.setPath("templates/it/same-run-a.ftl");
        changed.setContent("<#-- same-run-a: after the run -->\n");
        runJob("script-resource-import", List.of(changed), ended);
        assertEquals("<#-- same-run-a: the later archive's -->\n", content("templates/it/same-run-a.ftl"),
                "the ended run's token replaces no template");

        // The whole update, which the rule would have turned into a replace that keeps what is left out
        String categoryLog = runJob("category-import", List.of(Map.of("descs", List.of(desc("en", "It equal")),
                "type", "TEMPLATE", "siblingSortOrder", 6.0)), ended);
        assertEquals(Map.of(), categoryParams(category("It equal")), "the template parameter it leaves out removed");

        String typeLog = runJob("param-type-import", List.of(Map.of("type", "COMPOSITE", "name", "it_same",
                "templateParams", List.of(Map.of("paramId", "s2", "type", "text",
                        "descs", List.of(desc("en", "Second same")))))), ended);
        assertEquals(List.of("s2"), typeParamList("it_same").stream().map(Param::paramId).toList(),
                "the type's template parameters replaced whole");

        String promulgationLog = runJob("promulgation-type-import", List.of(Map.of("typeId", "it-domains-only",
                "serviceId", "navtex", "name", "It domains only", "priority", 4, "active", true, "language", "en")),
                ended);
        assertEquals(List.of(), domainsOf("PromulgationType", "PromulgationType_id", "typeId", "it-domains-only"),
                "the domains it leaves out cleared");

        String reportLog = runJob("report-import", List.of(Map.of("reportId", "it-report", "name",
                "It report after the run", "sortOrder", 0, "templatePath", "templates/it/same-run-a.ftl",
                "properties", Map.of("c", "4"))), ended);
        assertEquals(Map.of("c", "4"), reportProperties("it-report"), "the properties replaced whole, not merged");

        for (String jobLog : List.of(categoryLog, typeLog, promulgationLog, reportLog)) {
            assertFalse(jobLog.contains(CREATED_IT), "no line of the first bootstrap's rule: " + jobLog);
        }
    }

    /**
     * The job logs of the run's later archive, by job: the first job of each name whose token names the run and the
     * archive (a later test's job may carry the same token)
     */
    private Map<String, String> theLaterArchivesJobLogs() throws Exception {
        List<BatchData> jobs = em.createQuery("select b from BatchData b order by b.id", BatchData.class)
                .getResultList();
        String first = jobs.stream()
                .map(b -> b.getProperties().get(BootstrapRun.PROPERTY))
                .filter(t -> t != null && String.valueOf(t).endsWith("/0"))
                .map(String::valueOf)
                .findFirst()
                .orElseThrow();
        String later = first.substring(0, first.lastIndexOf('/')) + "/1";
        Map<String, String> logs = new LinkedHashMap<>();
        for (BatchData job : jobs) {
            if (later.equals(String.valueOf(job.getProperties().get(BootstrapRun.PROPERTY)))
                    && !logs.containsKey(job.getJobName())) {
                // A line ends as the platform ends it: one line separator for the assertions
                logs.put(job.getJobName(), logSince(job.getInstanceId(), logsBeforeTheRun).replace("\r\n", "\n"));
            }
        }
        return logs;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            count++;
        }
        return count;
    }

    /**
     * The job log of each of the later archive's imports says, a line per entry the earlier archive created, what was
     * added, what was replaced (the old value and the new) and what was kept (the ruling of 2026-10-24)
     */
    @Test
    @Order(11)
    void theRunsJobLogsSayWhatWasAddedAndReplaced() throws Exception {
        Map<String, String> logs = theLaterArchivesJobLogs();

        String templates = logs.get("script-resource-import");
        assertTrue(templates.contains("Replaced" + CREATED_IT + " templates/it/same-run-a.ftl"), templates);

        String dictionary = logs.get("dictionary-import");
        assertTrue(dictionary.contains("Dictionary it-same-run: added 1, overwritten 0, kept 0 (not ticked), "
                + "not added 0 (not ticked), unchanged 3, replaced in 4, languages added to 1 (an earlier archive of "
                + "this bootstrap run created them)"), "k3 added; k5, k6 and k9 unchanged; k1, k2, k7 and k8 replaced; "
                + "k1 gained a language: " + dictionary);
        for (String note : List.of("Replaced the en of k1" + CREATED_IT + ": one → ONE",
                "Replaced the en long value of k1" + CREATED_IT + ": the one → the ONE",
                "Added da to k1" + CREATED_IT,
                "Replaced the da of k2" + CREATED_IT + ": to → TO",
                "Replaced the en of k7" + CREATED_IT + ": seven → SEVEN",
                "Replaced the AtoN filter of k8" + CREATED_IT + ": f1 → f2")) {
            assertTrue(dictionary.contains("Dictionary it-same-run: " + note), note + ": " + dictionary);
        }
        assertFalse(dictionary.contains("of k5"), "k5 is unchanged: " + dictionary);
        assertFalse(dictionary.contains("k9"), "k9's empty values are left out: " + dictionary);
        assertFalse(dictionary.contains("long value of k7"), "k7's long value is kept: " + dictionary);
        // A start-up default text the later archive restates: kept by start-up's rules, and said so
        assertTrue(dictionary.contains("Dictionary web: added 0, overwritten 0, kept 1 (not ticked), not added 0 "
                + "(not ticked), unchanged 0\n"), dictionary);
        assertTrue(dictionary.contains("Dictionary web: Kept menu.atons (start-up created it, not this bootstrap "
                + "run: start-up's rules apply)\n"), dictionary);

        String categories = logs.get("category-import");
        assertEquals(1, occurrences(categories, "'It root'" + CREATED_IT),
                "the root is imported once for each of its two leaves and logged once: " + categories);
        assertTrue(categories.contains("'It root'" + CREATED_IT + ": added da, domain it-later; "
                + "replaced the sort order 1.0 → 7.0, the fr name Racine → RACINE\n"), categories);
        assertTrue(categories.contains("'It child'" + CREATED_IT + ": added da, it_p da, template parameter it_q; "
                + "replaced the it_p en name It parameter → It PARAMETER, the script paths "
                + "[templates/it/same-run-a.ftl] → [templates/it/same-run-c.ftl]\n"), categories);
        assertTrue(categories.contains("'It sibling'" + CREATED_IT + ": nothing to add\n"), categories);
        assertTrue(categories.contains("'It equal'" + CREATED_IT + ": nothing to add\n"), categories);
        assertTrue(categories.contains("'It inactive'" + CREATED_IT + ": activated by its new active child "),
                categories);
        assertTrue(categories.contains("'It flags'" + CREATED_IT + ": replaced the it_n position-list flag "
                + "true → false\n"), categories);
        assertTrue(categories.contains("'It deactivated'" + CREATED_IT + ": replaced the active flag true → false\n"),
                categories);
        assertTrue(categories.contains("'It changes'" + CREATED_IT + ": replaced the type CATEGORY → TEMPLATE, the "
                + "AtoN filter it-filter-a → it-filter-b, the MRN none → urn:mrn:iho:category:xx:it-changes, the "
                + "standard fields [it-x] → [it-y], the editor fields [it-a] → [it-b, it-c]\n"), categories);
        assertTrue(categories.contains("'It mrn'" + CREATED_IT + ": nothing to add; kept the MRN (a matching key)\n"),
                categories);
        assertTrue(categories.contains("'It emptied'" + CREATED_IT + ": replaced the editor fields [it-a] → []\n"),
                categories);
        assertTrue(categories.contains("'It inactive kept'" + CREATED_IT + ": nothing to add\n"), categories);
        assertTrue(categories.contains("'It parent'" + CREATED_IT + ": made inactive with it: Category "
                + category("It active child") + " 'It active child' (TreeBaseEntity.updateActiveFlag; not covered by "
                + "the rule); replaced the active flag true → false\n"), categories);
        assertTrue(categories.contains("'It activated child'" + CREATED_IT + ": made active with it: Category "
                + category("It inactive parent") + " 'It inactive parent' (TreeBaseEntity.updateActiveFlag; not "
                + "covered by the rule); replaced the active flag false → true\n"), categories);
        assertTrue(categories.contains("'It inactive parent'" + CREATED_IT + ": nothing to add\n"), categories);

        String types = logs.get("param-type-import");
        assertTrue(types.contains("Parameter type it_composite" + CREATED_IT + ": added p1 da, p1 da, p3; "
                + "replaced the p1 en name First → FIRST\n"), types);
        assertTrue(types.contains("Parameter type it_list" + CREATED_IT + ": replaced its values [k1] → [k1, k2]\n"),
                types);
        assertTrue(types.contains("Parameter type it_same" + CREATED_IT + ": nothing to add\n"), types);
        assertTrue(types.contains("Parameter type it_mandatory" + CREATED_IT + ": added m1 da\n"), types);
        assertTrue(types.contains("Parameter type it_typed" + CREATED_IT + ": replaced the t1 type text → number\n"),
                types);
        assertTrue(types.contains("Parameter type it_list_emptied" + CREATED_IT
                + ": replaced its values [k1] → []\n"), types);

        String promulgationTypes = logs.get("promulgation-type-import");
        assertTrue(promulgationTypes.contains("Promulgation type it-navtex" + CREATED_IT + ": added domain "
                + "it-later; replaced the priority 1 → 5; kept domain it-missing not found\n"), promulgationTypes);
        assertTrue(promulgationTypes.contains("Promulgation type it-domains-only" + CREATED_IT + ": added domain "
                + "it-later\n"), promulgationTypes);
        assertTrue(promulgationTypes.contains("Promulgation type it-flags" + CREATED_IT + ": replaced the priority "
                + "6 → 0, the active flag true → false, the requirement none → MANDATORY\n"), promulgationTypes);
        assertTrue(promulgationTypes.contains("Promulgation type it-changes" + CREATED_IT + ": replaced the name "
                + "It changes → It changes renamed, the service navtex → twitter, the requirement OPTIONAL → "
                + "MANDATORY, the language en → da, the message types [LOCAL_WARNING] → [COASTAL_WARNING, "
                + "NAVAREA_WARNING], the script paths [templates/it/same-run-a.ftl] → "
                + "[templates/it/same-run-b.ftl]\n"), promulgationTypes);
        assertTrue(promulgationTypes.contains("Promulgation type it-emptied" + CREATED_IT + ": replaced the message "
                + "types [LOCAL_WARNING] → [], the script paths [templates/it/same-run-a.ftl] → []\n"),
                promulgationTypes);

        String reports = logs.get("report-import");
        assertTrue(reports.contains("Report it-report" + CREATED_IT + ": added domain it-later; replaced the name "
                + "It report → It report renamed, property b 2 → 3; kept domain it-missing not found\n"), reports);
        assertTrue(reports.contains("Report it-report-changes" + CREATED_IT + ": added parameter q; replaced the sort "
                + "order 1 → 2, the template path templates/it/same-run-a.ftl → templates/it/same-run-b.ftl\n"),
                reports);
    }
}
