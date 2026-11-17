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

import liquibase.change.core.SQLFileChange;
import liquibase.change.custom.CustomSqlChange;
import liquibase.database.Database;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import liquibase.statement.SqlStatement;

/**
 * Loads an SQL file into the database exactly as the file says: the integration suite's fixture
 * ({@code db/niord-base-data.sql}, loaded by {@code db/changelog/changelog-with-seed.yaml}).
 *
 * <p>Liquibase's own {@code sqlFile} change replaces every {@code ${name}} in the file with the changelog parameter of
 * that name, and Liquibase makes every environment variable and every system property a changelog parameter, the
 * name matched without regard to case. The fixture is a dump of the base product's templates and scripts, whose
 * FreeMarker is full of {@code ${...}}: on GitHub's Ubuntu runners, whose {@code LANG} is {@code C.UTF-8}, every
 * {@code ${lang}} of {@code common.ftl} reached the database as {@code C.UTF-8}, and the subject field templates
 * printed {@code part.getDesc('C.UTF-8')} (seen in a CI run). Any other name the templates use, such as
 * {@code ${title}}, {@code ${year}} or {@code ${number}}, would be taken the same way from a machine that sets it.
 * This change splits and runs the file as {@code sqlFile} does (statements split at {@code ;}, comments kept), but
 * reads it without the expansion. No deployment runs it: deployments use the bare master changelog, and their
 * templates arrive by the bootstrap, which no changelog parameter reaches.
 */
public class VerbatimSqlFileChange implements CustomSqlChange {

    private String path;

    /** The file's class-path location, as {@code sqlFile}'s {@code path} with {@code relativeToChangelogFile: false} */
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public SqlStatement[] generateStatements(Database database) {
        return verbatimSqlFile(path).generateStatements(database);
    }

    /**
     * Returns Liquibase's {@code sqlFile} change for the file, with the settings the fixture was always loaded with,
     * that reads the file without expanding its {@code ${...}}
     *
     * @param path the file's class-path location
     * @return the change
     */
    static SQLFileChange verbatimSqlFile(String path) {
        SQLFileChange change = new SQLFileChange() {
            @Override
            public String getSql() {
                return getSql(false);
            }
        };
        change.setPath(path);
        change.setRelativeToChangelogFile(false);
        change.setSplitStatements(true);
        change.setEndDelimiter(";");
        change.setStripComments(false);
        return change;
    }

    @Override
    public String getConfirmationMessage() {
        return "Loaded " + path + " as it is";
    }

    @Override
    public void setUp() {
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
    }

    @Override
    public ValidationErrors validate(Database database) {
        ValidationErrors errors = new ValidationErrors();
        if (path == null || path.isBlank()) {
            errors.addError("path is required");
        }
        return errors;
    }
}
