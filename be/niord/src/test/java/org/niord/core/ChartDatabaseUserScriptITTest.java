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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.niord.test.NiordTestContainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chart's database-user script ({@code niord.postgres.userScript} in {@code be/k8s/helm/niord/templates/_helpers.tpl}),
 * run as the init containers of the app's and Keycloak's pods run it: in the chart's Postgres image, as the image's
 * postgres user, connected as the superuser, with the environment they give it ({@code docs/database-user-plan.md}).
 * <p>
 * It runs before every start, as the superuser, inside the database that the application's user owns. Someone who can
 * run SQL as that user can put an operator where the superuser's session would find one: in the schema {@code public},
 * in a schema named after the superuser, or in a schema of their own that a search path set on the database names. The
 * script holds its search path to {@code pg_catalog}, so its next run does not call what was planted there (SECREV-4,
 * {@code docs/security-review-2026-09.md} section 5), and the objects it hands over still reach the user.
 * <p>
 * The define holds no template directive, so its text is what Helm renders; the test reads it from the chart.
 */
class ChartDatabaseUserScriptITTest {

    /** The chart's helpers, from niord-base's directory, where Failsafe runs */
    private static final Path HELPERS = Path.of("../k8s/helm/niord/templates/_helpers.tpl");

    /** The superuser the Postgres image creates; the chart's local default for postgres.admin */
    private static final String SUPERUSER = "niord";

    private static final String SUPERUSER_PASSWORD = "niord";

    /** The application's user's password in every case */
    private static final String PASSWORD = "app";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("imresamu/postgis:18-3.6")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("niord")
                    .withUsername(SUPERUSER)
                    .withPassword(SUPERUSER_PASSWORD);

    private static String script;

    @BeforeAll
    static void start() throws Exception {
        Assumptions.assumeTrue(NiordTestContainers.dockerAvailable(),
                "Docker is not available - skipping Niord integration tests");
        script = userScript(Files.readString(HELPERS).replace("\r\n", "\n"));
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    /** Where the application's user puts its operators, and what else it does so the superuser's session finds them */
    enum Planted {
        PUBLIC("public", ""),
        SCHEMA_NAMED_AFTER_THE_SUPERUSER(SUPERUSER, "CREATE SCHEMA " + SUPERUSER),
        SCHEMA_THE_DATABASE_SEARCH_PATH_NAMES("planted",
                "CREATE SCHEMA planted; ALTER DATABASE %s SET search_path = planted, public");

        final String schema;
        final String setUp;

        Planted(String schema, String setUp) {
            this.schema = schema;
            this.setUp = setUp;
        }

        String database() {
            return "planted_" + ordinal();
        }

        String user() {
            return "app_" + ordinal();
        }
    }

    @ParameterizedTest
    @EnumSource(Planted.class)
    void theNextRunCallsNothingTheApplicationsUserPlanted(Planted planted) throws Exception {
        String user = planted.user();
        String database = planted.database();
        runScript(user, database);

        // As the application's user: = and <> between an oid and each reg type the catalogs are compared with. Each
        // records who called it and, called by a superuser, makes the application's user one.
        try (Connection c = connect(database, user, PASSWORD); Statement st = c.createStatement()) {
            if (!planted.setUp.isEmpty()) {
                st.execute(planted.setUp.formatted(database));
            }
            st.execute("CREATE TABLE public.planted_calls (caller text)");
            st.execute("CREATE FUNCTION " + planted.schema + ".planted(oid, oid) RETURNS boolean LANGUAGE plpgsql AS $f$"
                    + " BEGIN"
                    + "   INSERT INTO public.planted_calls VALUES (current_user);"
                    + "   IF (SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = current_user) THEN"
                    + "     EXECUTE 'ALTER ROLE " + user + " SUPERUSER';"
                    + "   END IF;"
                    + "   RETURN $1 OPERATOR(pg_catalog.=) $2;"
                    + " END $f$");
            for (String type : List.of("regclass", "regnamespace", "regrole")) {
                st.execute("CREATE FUNCTION " + planted.schema + ".planted(oid, " + type + ") RETURNS boolean"
                        + " LANGUAGE sql AS $f$ SELECT " + planted.schema + ".planted($1, $2::oid) $f$");
                for (String operator : List.of("=", "<>")) {
                    st.execute("CREATE OPERATOR " + planted.schema + "." + operator + " (LEFTARG = oid, RIGHTARG = "
                            + type + ", FUNCTION = " + planted.schema + ".planted)");
                }
            }
        }

        runScript(user, database);

        try (Connection c = connect(database, SUPERUSER, SUPERUSER_PASSWORD); Statement st = c.createStatement()) {
            assertEquals(List.of(), strings(st, "SELECT DISTINCT caller FROM public.planted_calls"),
                    "the script's session called an operator the application's user planted");
            assertEquals(List.of("false"), strings(st, "SELECT rolsuper::text FROM pg_roles WHERE rolname = '" + user + "'"),
                    "the application's user is no superuser");
        }
    }

    @Test
    void handsOverEveryKindOfObjectOfADatabaseTheSuperuserMade() throws Exception {
        String user = "app_hand_over";
        String database = "hand_over";
        try (Connection c = POSTGRES.createConnection(""); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + database + " TEMPLATE template_postgis");
        }
        // As a restore or an earlier chart leaves them: mixed-case names, owned by the superuser
        try (Connection c = connect(database, SUPERUSER, SUPERUSER_PASSWORD); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TYPE public."MsgStatus" AS ENUM ('DRAFT', 'PUBLISHED');
                    CREATE DOMAIN public."Pos" AS int CHECK (VALUE > 0);
                    CREATE TYPE public."Pair" AS (a int, b text);
                    CREATE TABLE public."Message" (id serial PRIMARY KEY, status public."MsgStatus", p public."Pos", g geometry);
                    CREATE SEQUENCE public."FreeSeq";
                    CREATE VIEW public."MessageView" AS SELECT * FROM public."Message";
                    CREATE MATERIALIZED VIEW public."MessageIds" AS SELECT id FROM public."Message";
                    CREATE TABLE public."Part" (id int) PARTITION BY RANGE (id);
                    CREATE FUNCTION public."statusName"(s public."MsgStatus", q public."Pair") RETURNS text
                        LANGUAGE sql AS $$ SELECT s::text $$;
                    """);
        }

        String first = runScript(user, database).getStdout();
        assertTrue(first.contains("database " + database + " handed over to " + user), first);
        // The serial's sequence follows its table
        assertTrue(first.contains("9 tables, sequences, views, functions and types of " + database
                + " handed over to " + user), first);

        try (Connection c = connect(database, SUPERUSER, SUPERUSER_PASSWORD); Statement st = c.createStatement()) {
            assertEquals(List.of(), strings(st, """
                    SELECT o.name || ' ' || pg_get_userbyid(o.owner) FROM (
                        SELECT c.oid::regclass::text AS name, c.relowner AS owner, 'pg_class'::regclass AS catalog, c.oid
                          FROM pg_class c WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'S', 'v', 'm')
                        UNION ALL
                        SELECT p.oid::regprocedure::text, p.proowner, 'pg_proc'::regclass, p.oid
                          FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace
                        UNION ALL
                        SELECT t.oid::regtype::text, t.typowner, 'pg_type'::regclass, t.oid
                          FROM pg_type t WHERE t.typnamespace = 'public'::regnamespace AND t.typtype IN ('e', 'd', 'c')
                    ) o
                     WHERE pg_get_userbyid(o.owner) <> '%s'
                       AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = o.catalog AND d.objid = o.oid AND d.deptype = 'e')
                       AND NOT EXISTS (SELECT 1 FROM pg_class r WHERE r.reltype = o.oid AND r.relkind <> 'c')
                     ORDER BY 1
                    """.formatted(user)), "left with another owner; only the extensions' objects may be");
        }

        String second = runScript(user, database).getStdout();
        assertFalse(second.contains("handed over"), "a second run has nothing to hand over: " + second);
    }

    /** The define's text, between its opening and its end */
    static String userScript(String helpers) {
        String opening = "{{- define \"niord.postgres.userScript\" -}}\n";
        int from = helpers.indexOf(opening);
        if (from < 0) {
            throw new IllegalStateException(HELPERS + " no longer defines niord.postgres.userScript");
        }
        from += opening.length();
        String body = helpers.substring(from, helpers.indexOf("\n{{- end }}", from) + 1);
        if (body.contains("{{")) {
            throw new IllegalStateException("niord.postgres.userScript holds a template directive now: "
                    + "this test reads the define's text and would have to render the chart instead");
        }
        return body;
    }

    /** Runs the script as the app pod's database-user init container does (templates/app/deployment.yaml) */
    private static Container.ExecResult runScript(String user, String database) throws Exception {
        Container.ExecResult result = POSTGRES.execInContainer(ExecConfig.builder()
                // runAsUser 999, the image's own postgres user
                .user("postgres")
                .envVars(Map.of(
                        "PGHOST", "localhost",
                        "PGPORT", "5432",
                        "PGUSER", SUPERUSER,
                        "PGPASSWORD", SUPERUSER_PASSWORD,
                        "PGDATABASE", "postgres",
                        "DB_USER", user,
                        "DB_PASSWORD", PASSWORD,
                        "DATABASE", database,
                        "TEMPLATE", "template_postgis"))
                .command(new String[] { "sh", "-c", script })
                .build());
        assertEquals(0, result.getExitCode(),
                () -> "the script failed:\n" + result.getStdout() + result.getStderr());
        return result;
    }

    private static Connection connect(String database, String user, String password) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database, user, password);
    }

    private static List<String> strings(Statement st, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
