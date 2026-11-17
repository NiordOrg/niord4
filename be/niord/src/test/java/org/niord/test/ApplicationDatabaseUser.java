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

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The database user a new deployment's application connects as ({@code docs/database-user-plan.md}): a user
 * that owns the application's database and is not a superuser. The chart creates it beside the Postgres
 * image's superuser, and a hosted database comes with one; either way the extension is there already, created
 * by a superuser, and the application's user builds the schema and nothing more.
 * <p>
 * The Postgres container of a test starts with one user, the image's superuser, which also owns the database
 * the image creates. {@link #of} makes the application's user in a started container and hands it that
 * database, as the chart does on a new volume. A test of the new-deployment path (the first start, the
 * bootstrap) then takes its datasource user and password from here instead of from the container, so a
 * changeset or a start-up statement that needs a superuser fails in the suite and not at a deployment's first
 * start.
 * <p>
 * The seeded suite ({@link ITTest}) keeps the container's superuser: its fixture is loaded with the
 * foreign-key triggers disabled, which only a superuser may do, and no deployment loads a fixture.
 */
public final class ApplicationDatabaseUser {

    /** The user's name, the chart's default for it. */
    public static final String NAME = "niord_app";

    /** Its password in a test container. */
    public static final String PASSWORD = "niord_app";

    /** The containers that have the user already, by JDBC URL. */
    private static final Set<String> READY = ConcurrentHashMap.newKeySet();

    private static final ApplicationDatabaseUser USER = new ApplicationDatabaseUser();

    private ApplicationDatabaseUser() {
    }

    /**
     * Returns the application's user of a started container, after creating it and handing it the container's
     * database the first time. The database must still be as the image made it: only the database itself
     * changes owner, which is the whole hand-over while it holds no table.
     */
    public static synchronized ApplicationDatabaseUser of(PostgreSQLContainer<?> postgres) {
        if (READY.add(postgres.getJdbcUrl())) {
            try (Connection c = postgres.createConnection(""); Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + NAME + "'")) {
                    if (!rs.next()) {
                        st.execute("CREATE ROLE " + NAME + " LOGIN PASSWORD '" + PASSWORD
                                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
                    }
                }
                st.execute("ALTER DATABASE \"" + postgres.getDatabaseName() + "\" OWNER TO " + NAME);
            } catch (SQLException e) {
                READY.remove(postgres.getJdbcUrl());
                throw new IllegalStateException("Could not create the application's database user in "
                        + postgres.getJdbcUrl(), e);
            }
        }
        return USER;
    }

    public String name() {
        return NAME;
    }

    public String password() {
        return PASSWORD;
    }
}
