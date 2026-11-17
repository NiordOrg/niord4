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
package org.niord.test;

import liquibase.Scope;
import liquibase.change.Change;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.core.PostgresDatabase;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.RawSqlStatement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The integration suite's fixture reaches the database as the file says, whatever the machine's environment holds.
 * Liquibase makes every environment variable and system property a changelog parameter, matched without regard to
 * case, and its {@code sqlFile} change replaced each {@code ${name}} of the fixture with it: on GitHub's runners
 * ({@code LANG=C.UTF-8}) the {@code ${lang}} of {@code common.ftl}'s subject field templates reached the database as
 * {@code C.UTF-8} (seen in a CI run). The test sets the system property {@code lang}, which Liquibase reads the
 * same way as the environment's {@code LANG}, while the changelog is parsed, and reads the statements the seed
 * changeset sends: the template rows keep {@code ${lang}}, and nothing says {@code C.UTF-8}.
 */
class SeedFixtureVerbatimTest {

    private static final String CHANGELOG = "db/changelog/changelog-with-seed.yaml";

    /** What the machine's LANG was on the runner, given here as the system property Liquibase reads alike */
    private static final String RUNNER_LANG = "C.UTF-8";

    @Test
    void theSeedKeepsTheTemplatesTokens_whateverTheEnvironmentNamesTheSame() throws Exception {
        List<String> sql = seedStatements(Map.of("lang", RUNNER_LANG, "title", "A title from outside"));

        assertTrue(sql.size() > 100, "the seed changeset sends the fixture's statements: " + sql.size());
        assertTrue(sql.stream().anyMatch(s -> s.contains("getDesc(''${lang}'').subject")),
                "common.ftl's subject field templates keep ${lang}");
        assertTrue(sql.stream().anyMatch(s -> s.contains("${title}")), "a template's ${title} is kept");
        assertFalse(sql.stream().anyMatch(s -> s.contains(RUNNER_LANG)), "nothing in the seed says " + RUNNER_LANG);
        assertFalse(sql.stream().anyMatch(s -> s.contains("A title from outside")), "no ${title} was replaced");
    }

    /**
     * The SQL the {@code seed-base-data} changeset sends, with the given system properties set while Liquibase reads
     * them (when its changelog parameters are made) and restored after
     */
    private static List<String> seedStatements(Map<String, String> properties) throws Exception {
        ResourceAccessor accessor = new ClassLoaderResourceAccessor(SeedFixtureVerbatimTest.class.getClassLoader());
        PostgresDatabase database = new PostgresDatabase();
        ChangeLogParameters parameters;
        Map<String, String> before = new java.util.HashMap<>();
        properties.forEach((key, value) -> before.put(key, System.getProperty(key)));
        try {
            properties.forEach(System::setProperty);
            parameters = new ChangeLogParameters(database);
        } finally {
            before.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
        Map<String, Object> scope = Map.of(Scope.Attr.resourceAccessor.name(), accessor);
        return Scope.child(scope, () -> {
            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance().getParser(CHANGELOG, accessor)
                    .parse(CHANGELOG, parameters, accessor);
            List<ChangeSet> seed = changeLog.getChangeSets().stream()
                    .filter(cs -> "seed-base-data".equals(cs.getId()))
                    .toList();
            assertEquals(1, seed.size(), "one seed-base-data changeset");
            List<String> sql = new ArrayList<>();
            for (Change change : seed.get(0).getChanges()) {
                for (SqlStatement statement : change.generateStatements(database)) {
                    if (statement instanceof RawSqlStatement raw) {
                        sql.add(raw.getSql());
                    }
                }
            }
            return sql;
        });
    }
}
