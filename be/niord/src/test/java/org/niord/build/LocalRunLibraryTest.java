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
package org.niord.build;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The launcher library, be/scripts/local-run-lib.sh, which the repository root's {@code start-local.sh} and a
 * country's launcher source. The shared containers niord-db, niord-keycloak and niord-smtp serve every checkout and
 * every country, so {@code start_services} never stops, restarts or recreates a running one: while all three run it
 * issues no compose command at all, and a missing or stopped one is started alone with {@code up --no-recreate}, the
 * compose file's own project name in force (COMPOSE_PROJECT_NAME unset). The realm is copied to the one per-user path
 * the compose file mounts first, and the compose file names that path and the project {@code be}. The library runs against a {@code docker} first on the PATH that writes down each
 * call and answers {@code inspect} from DOCKER_STOPPED and DOCKER_MISSING, so no real Docker is ever reached. Also:
 * {@code start-local.sh} sources the library and redefines none of its functions, and its {@code --dk} and
 * {@code --gl} name the country's own launcher before any docker call; {@code sample_reset} drops only
 * {@code niord_sample} or {@code niord_sample_<cc>}, refusing any other name before any docker call. And the library
 * makes the local Keycloak client of each active domain a country's bootstrap folder brings, its kcadm calls answered
 * by the same stand-in, never updating or deleting anything. Skipped where no bash is found, as
 * {@link LoadSampleScriptTest} is.
 */
class LocalRunLibraryTest {

    /** The library and the launcher; the tests run in the module's folder, be/niord */
    static final Path LIBRARY = Path.of("../scripts/local-run-lib.sh");
    static final Path LAUNCHER = Path.of("../../start-local.sh");
    static final Path COMPOSE = Path.of("../docker-compose.yml");
    static final Path REALM = Path.of("../keycloak/niord-realm-local.json");

    static final List<String> SHARED = List.of("niord-db", "niord-keycloak", "niord-smtp");

    @TempDir
    Path dir;

    Path shims;

    /** Where the docker stand-in writes each call's arguments, with the COMPOSE_PROJECT_NAME it saw */
    Path dockerCalls;

    /** The home the library resolves ~ to, HOME and USERPROFILE both */
    Path home;

    /** The outcome of a run */
    record Result(int status, String output, List<String> calls) {
        @Override
        public String toString() {
            return "exit " + status + "\n" + output + "\ndocker calls:\n" + String.join("\n", calls);
        }
    }

    @BeforeEach
    void docker() throws IOException {
        shims = Files.createDirectories(dir.resolve("bin"));
        home = Files.createDirectories(dir.resolve("home"));
        dockerCalls = dir.resolve("docker-calls");
        // Paths with forward slashes, which Git Bash reads as well as any other bash
        String calls = slashes(dockerCalls);
        String up = slashes(dir.resolve("up"));
        // The clients the stand-in's kcadm has made, one id a line, so that a second run finds them
        String created = slashes(dir.resolve("kc-created"));
        Path docker = shims.resolve("docker");
        Files.writeString(docker, "#!/bin/sh\n"
                + "echo \"$* MSYS_NO_PATHCONV=${MSYS_NO_PATHCONV-unset} COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME-unset}\""
                + " >> '" + calls + "'\n"
                + "case \"$1\" in\n"
                + "    inspect)\n"
                + "        for name; do :; done\n"
                + "        if [ -f '" + up + "' ]; then echo 'running healthy'; exit 0; fi\n"
                + "        case \" ${DOCKER_STOPPED:-} \" in *\" $name \"*) echo 'exited none'; exit 0 ;; esac\n"
                + "        case \" ${DOCKER_MISSING:-} \" in\n"
                + "            *\" $name \"*) echo \"Error: No such object: $name\" >&2; exit 1 ;;\n"
                + "        esac\n"
                + "        echo 'running healthy'; exit 0 ;;\n"
                + "    compose) touch '" + up + "'; exit 0 ;;\n"
                + "    exec)\n"
                + "        [ \"$2\" = niord-keycloak ] || exit 1\n"
                + "        if [ -n \"${KC_DOWN:-}\" ]; then\n"
                + "            echo 'Error response from daemon: container niord-keycloak is not running' >&2; exit 1\n"
                + "        fi\n"
                + "        shift 3\n"
                + "        case \"$1 $2\" in\n"
                + "            'config credentials') exit 0 ;;\n"
                + "            'get clients')\n"
                + "                ids=\"${KC_EXISTING:-}\"\n"
                + "                if [ -f '" + created + "' ]; then ids=\"$ids $(cat '" + created + "')\"; fi\n"
                + "                sep='['\n"
                + "                for c in $ids; do\n"
                + "                    printf '%s {\\n  \"id\" : \"id-%s\",\\n  \"clientId\" : \"%s\"\\n}' \"$sep\" \"$c\" \"$c\"\n"
                + "                    sep=','\n"
                + "                done\n"
                + "                if [ \"$sep\" = '[' ]; then echo '[ ]'; else echo ' ]'; fi\n"
                + "                exit 0 ;;\n"
                + "            'create clients')\n"
                + "                for a; do case \"$a\" in clientId=*) c=\"${a#clientId=}\" ;; esac; done\n"
                + "                echo \"$c\" >> '" + created + "'; echo \"id-$c\"; exit 0 ;;\n"
                + "            'create clients/'*'/roles')\n"
                + "                [ -z \"${KC_FAIL_ROLES:-}\" ] || { echo 'HTTP 500 Internal Server Error' >&2; exit 1; }\n"
                + "                for a; do case \"$a\" in name=*) echo \"${a#name=}\" ;; esac; done\n"
                + "                exit 0 ;;\n"
                + "            'get clients/'*'/roles')\n"
                + "                echo '[ {\"id\":\"rid-user\",\"name\":\"user\"}, {\"id\":\"rid-editor\",\"name\":\"editor\"},"
                + " {\"id\":\"rid-admin\",\"name\":\"admin\"}, {\"id\":\"rid-sysadmin\",\"name\":\"sysadmin\"},"
                + " {\"id\":\"rid-publication-curate\",\"name\":\"publication-curate\"} ]'\n"
                + "                exit 0 ;;\n"
                + "            'add-roles '*) exit 0 ;;\n"
                + "            *) exit 1 ;;\n"
                + "        esac ;;\n"
                + "    *) exit 1 ;;\n"
                + "esac\n", StandardCharsets.UTF_8);
        docker.toFile().setExecutable(true);
    }

    @Test
    void allThreeRunningIssuesNoComposeCommand() throws Exception {
        Result result = startServices(Map.of());

        assertEquals(0, result.status(), result.toString());
        assertFalse(result.calls().isEmpty(), "the containers are asked: " + result);
        for (String call : result.calls()) {
            assertTrue(call.startsWith("inspect "), "only inspect calls: " + result);
            assertFalse(call.matches("^(compose|start|stop|restart|rm|down)\\b.*"), result.toString());
        }
        for (String name : SHARED) {
            assertTrue(result.calls().stream().anyMatch(call -> call.contains(" " + name + " ")),
                    name + " is asked: " + result);
        }
    }

    @Test
    void aStoppedOneIsStartedByComposeUpAlone() throws Exception {
        Result result = startServices(Map.of("DOCKER_STOPPED", "niord-smtp"));

        assertEquals(0, result.status(), result.toString());
        List<String> compose = composeCalls(result);
        assertEquals(1, compose.size(), result.toString());
        String call = compose.getFirst();
        assertTrue(call.contains("-f " + slashes(COMPOSE)), result.toString());
        assertTrue(call.contains("up -d --wait --no-recreate niord-smtp "), result.toString());
        assertFalse(call.contains("niord-db") || call.contains("niord-keycloak"), "the running ones untouched: " + result);
        assertTrue(call.endsWith(" COMPOSE_PROJECT_NAME=unset"), "the file's project name in force: " + result);
        assertTrue(result.calls().stream().allMatch(c -> c.startsWith("inspect ") || c.startsWith("compose ")),
                "no other docker command: " + result);
    }

    @Test
    void aMissingOneIsCreatedWithTheRealmStaged() throws Exception {
        Result result = startServices(Map.of("DOCKER_MISSING", "niord-keycloak"));

        assertEquals(0, result.status(), result.toString());
        List<String> compose = composeCalls(result);
        assertEquals(1, compose.size(), result.toString());
        assertTrue(compose.getFirst().contains(" up -d --wait --no-recreate niord-keycloak "), result.toString());
        assertFalse(compose.getFirst().contains("niord-db") || compose.getFirst().contains("niord-smtp"),
                result.toString());
        Path staged = home.resolve(".niord-local/keycloak/niord-realm-local.json");
        assertTrue(Files.isRegularFile(staged), "the realm is staged under the home: " + result);
        assertArrayEquals(Files.readAllBytes(REALM), Files.readAllBytes(staged), "a byte-equal copy");
    }

    @Test
    void theLauncherDefinesNoneOfTheLibrarysFunctions() throws IOException {
        String library = Files.readString(LIBRARY, StandardCharsets.UTF_8);
        String launcher = Files.readString(LAUNCHER, StandardCharsets.UTF_8);

        assertTrue(launcher.contains("source \"$ROOT/be/scripts/local-run-lib.sh\""), "start-local.sh sources the library");
        List<String> functions = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?m)^([a-z_0-9]+)\\(\\) \\{").matcher(library);
        while (matcher.find()) {
            functions.add(matcher.group(1));
        }
        assertTrue(functions.containsAll(List.of("json_field", "bootstrap_outcome", "bootstrap_database",
                "start_services", "stage_realm", "kill_tree", "port_in_use", "wait_for", "sample_reset",
                "ensure_domain_clients")),
                functions.toString());
        for (String function : functions) {
            assertFalse(Pattern.compile("(?m)^\\s*(function\\s+)?" + function + "\\s*\\(\\)").matcher(launcher).find(),
                    "start-local.sh defines the library's " + function + " again");
        }
    }

    @Test
    void theComposeFileNamesTheProjectAndMountsTheStagedRealm() throws Exception {
        String yaml = Files.readString(COMPOSE, StandardCharsets.UTF_8);

        // The project the three containers and their volumes carry, from any folder
        assertTrue(Pattern.compile("(?m)^name: be$").matcher(yaml).find(), "the top-level name: be");
        assertTrue(yaml.contains("source: ~/.niord-local/keycloak/niord-realm-local.json"), "the per-user realm");
        assertTrue(yaml.contains("target: /opt/keycloak/data/import/niord-realm.json"), "mounted where Keycloak imports");
        assertTrue(yaml.contains("create_host_path: false"), "a missing copy fails the up, no folder in its place");

        // The library stages the realm to the same path, under the home compose resolves ~ to
        Path driver = dir.resolve("realm-path.sh");
        Files.writeString(driver, "set -euo pipefail\nsource \"$1\"\nnative_path \"$(local_realm_path)\"\n",
                StandardCharsets.UTF_8);
        Result result = run(List.of(slashes(driver), slashes(LIBRARY)), Map.of());
        assertEquals(0, result.status(), result.toString());
        assertEquals(slashes(home) + "/.niord-local/keycloak/niord-realm-local.json", result.output().strip(),
                result.toString());
    }

    @Test
    void theCountryFlagsNameTheCountryLauncher() throws Exception {
        // A docker that fails and writes down that it was called
        Files.writeString(shims.resolve("docker"), "#!/bin/sh\necho \"$*\" >> '" + slashes(dockerCalls) + "'\nexit 1\n",
                StandardCharsets.UTF_8);
        for (String country : List.of("dk", "gl")) {
            Result result = run(List.of(slashes(LAUNCHER), "--" + country), Map.of());
            assertEquals(2, result.status(), result.toString());
            assertTrue(result.output().contains("./start-local.sh")
                    && result.output().contains("SIFA-DK/niord-" + country), result.toString());
            assertTrue(result.calls().isEmpty(), "refused before any docker call: " + result);
        }
    }

    @Test
    void sampleResetRefusesAnyDatabaseButASampleOneBeforeDocker() throws Exception {
        for (String db : List.of("niord", "niord_test", "niord_gl", "niord_sample_DK", "niord_sample_dnk")) {
            Result result = sampleReset(db);
            assertEquals(1, result.status(), db + ": " + result);
            assertTrue(result.output().contains("sample_reset drops only the sample's database, niord_sample or "
                    + "niord_sample_<cc>, not '" + db + "'"), db + ": " + result);
            assertTrue(result.calls().isEmpty(), "refused before any docker call: " + db + ": " + result);
        }
    }

    /** A country's sample database reaches docker: the session count first, which the stand-in fails */
    @Test
    void sampleResetOfACountrysSampleDatabaseAsksForItsSessions() throws Exception {
        Result result = sampleReset("niord_sample_dk");
        assertEquals(1, result.status(), result.toString());
        assertEquals(1, result.calls().size(), result.toString());
        assertTrue(result.calls().getFirst().startsWith("exec niord-db psql -U niord -d postgres -tAc select count(*) "
                + "from pg_stat_activity where datname = 'niord_sample_dk'"), result.toString());
        assertTrue(result.output().contains("A session is connected to niord_sample_dk"), result.toString());
        assertFalse(result.calls().stream().anyMatch(call -> call.contains("dropdb")), "nothing dropped: " + result);
    }

    /** One active domain, one inactive and one without the flag, which the backend imports as inactive */
    static final String DOMAINS = "[{\"domainId\":\"yy-nw\",\"name\":\"YY Navigational Warnings\",\"active\":true},"
            + "{\"domainId\":\"yy-old\",\"name\":\"Old\",\"active\":false},{\"domainId\":\"yy-unset\",\"name\":\"No flag\"}]";

    /** The two calls that find the clients present */
    static final List<String> SIGN_IN_AND_LIST = List.of(
            "config credentials --server http://localhost:8080/auth --realm master --user admin --password admin",
            "get clients -r niord --fields id,clientId");

    /** The kcadm calls for the missing client yy-nw, with the ids the stand-in hands back */
    static final List<String> YY_NW_MADE = List.of(
            SIGN_IN_AND_LIST.get(0),
            SIGN_IN_AND_LIST.get(1),
            "create clients -r niord -s clientId=yy-nw -s name=YY Navigational Warnings -s enabled=true"
                    + " -s bearerOnly=true -s publicClient=false -s protocol=openid-connect -s standardFlowEnabled=true"
                    + " -s directAccessGrantsEnabled=true -s fullScopeAllowed=false -i",
            "create clients/id-yy-nw/roles -r niord -s name=user -s description=User -i",
            "create clients/id-yy-nw/roles -r niord -s name=editor -s description=Editor -i",
            "create clients/id-yy-nw/roles -r niord -s name=admin -s description=Administrator -i",
            "create clients/id-yy-nw/roles -r niord -s name=sysadmin -s description=System administrator -i",
            "create clients/id-yy-nw/roles -r niord -s name=publication-curate -s description=Publication curator -i",
            "get clients/id-yy-nw/roles -r niord --fields id,name",
            "add-roles -r niord --rid rid-editor --cclientid yy-nw --rolename user",
            "add-roles -r niord --rid rid-admin --cclientid yy-nw --rolename editor --rolename publication-curate",
            "add-roles -r niord --rid rid-sysadmin --cclientid yy-nw --rolename editor --rolename admin"
                    + " --rolename publication-curate",
            "add-roles -r niord --gname Sysadmin --cclientid yy-nw --rolename sysadmin --rolename publication-curate");

    @Test
    void theDomainClientsAreMadeByOneFunctionCalledFromTheBootstrapStep() throws IOException {
        String library = Files.readString(LIBRARY, StandardCharsets.UTF_8);

        assertEquals(1, Pattern.compile("(?m)^ensure_domain_clients\\(\\) \\{").matcher(library).results().count(),
                "ensure_domain_clients is defined once");
        int start = library.indexOf("bootstrap_database() {");
        assertTrue(start >= 0, "the library defines bootstrap_database");
        Matcher end = Pattern.compile("(?m)^\\}").matcher(library);
        assertTrue(end.find(start), "bootstrap_database ends");
        assertTrue(library.substring(start, end.start()).contains("ensure_domain_clients"),
                "bootstrap_database makes the domain clients");
    }

    @Test
    void aMissingActiveDomainGetsItsClientItsRolesAndTheSysadminGroup() throws Exception {
        Result result = ensureDomainClients(domainsFile(DOMAINS), Map.of());

        assertEquals(0, result.status(), result.toString());
        assertEquals(YY_NW_MADE, kcadmCalls(result), result.toString());
        assertFalse(result.calls().stream().anyMatch(call -> call.contains("yy-old") || call.contains("yy-unset")),
                "an inactive domain gets no client: " + result);
        assertTrue(result.output().contains(">> Creating the local Keycloak client yy-nw"), result.toString());
        assertTrue(result.output().contains("ensure_domain_clients returned"), result.toString());
        assertNothingChanged(result);
    }

    @Test
    void anExistingClientIsLeftAsItIsAndASecondRunAddsNothing() throws Exception {
        Path domains = domainsFile(DOMAINS);
        Result existing = ensureDomainClients(domains, Map.of("KC_EXISTING", "niord-client-master yy-nw"));

        assertEquals(0, existing.status(), existing.toString());
        assertEquals(SIGN_IN_AND_LIST, kcadmCalls(existing), "nothing created, updated or deleted: " + existing);
        assertTrue(existing.output().contains("yy-nw exist: left as they are"), existing.toString());
        assertNothingChanged(existing);

        Files.delete(dockerCalls);
        Result first = ensureDomainClients(domains, Map.of());
        assertEquals(0, first.status(), first.toString());
        assertEquals(YY_NW_MADE, kcadmCalls(first), first.toString());
        Result second = ensureDomainClients(domains, Map.of());
        assertEquals(0, second.status(), second.toString());
        List<String> calls = kcadmCalls(second);
        assertEquals(SIGN_IN_AND_LIST, calls.subList(YY_NW_MADE.size(), calls.size()), "the second run adds nothing: "
                + second);
        assertTrue(second.output().contains("exist: left as they are"), second.toString());
        assertNothingChanged(second);
    }

    @Test
    void theBaseArchiveAndTheSampleRunMakeNoClient() throws Exception {
        Files.writeString(shims.resolve("curl"), "#!/bin/sh\nexit 7\n", StandardCharsets.UTF_8);
        shims.resolve("curl").toFile().setExecutable(true);
        Path country = domainsFile(DOMAINS).getParent();
        Path zip = Files.writeString(dir.resolve("base.zip"), "an archive", StandardCharsets.UTF_8);

        // One folder alone, a country's in the base archive's place, as --sample posts the base archive alone
        Result alone = bootstrapDatabase(List.of(country));
        assertBootstrapReturned(alone);
        assertTrue(alone.calls().stream().noneMatch(call -> call.startsWith("exec niord-keycloak")), alone.toString());

        // The root launcher's call: the base product's own bootstrap folder alone
        Result base = bootstrapDatabase(List.of(Path.of("bootstrap")));
        assertBootstrapReturned(base);
        assertTrue(base.calls().stream().noneMatch(call -> call.startsWith("exec niord-keycloak")), base.toString());

        // A country's start: the base archive, then the country's be/bootstrap/
        Result start = bootstrapDatabase(List.of(zip, country));
        assertBootstrapReturned(start);
        assertEquals(YY_NW_MADE, kcadmCalls(start), start.toString());
        assertNothingChanged(start);
    }

    @Test
    void aKeycloakThatCannotBeReachedIsReportedAndTheStartGoesOn() throws Exception {
        Result result = ensureDomainClients(domainsFile(DOMAINS), Map.of("KC_DOWN", "1"));

        assertEquals(0, result.status(), result.toString());
        assertTrue(result.output().contains("!! kcadm.sh in niord-keycloak could not sign in"), result.toString());
        assertTrue(result.output().contains("ensure_domain_clients returned"), "not fatal: " + result);
        assertEquals(List.of(SIGN_IN_AND_LIST.getFirst()), kcadmCalls(result), result.toString());
        assertNothingChanged(result);
    }

    @Test
    void rolesThatFailAreReportedAndNothingIsDeleted() throws Exception {
        Result result = ensureDomainClients(domainsFile(DOMAINS), Map.of("KC_FAIL_ROLES", "1"));

        assertEquals(0, result.status(), result.toString());
        assertTrue(result.output().contains("its roles are not all there"), result.toString());
        assertTrue(result.output().contains("never changed here"), result.toString());
        assertTrue(result.output().contains("ensure_domain_clients returned"), "not fatal: " + result);
        assertTrue(kcadmCalls(result).stream().noneMatch(call -> call.startsWith("add-roles ")), result.toString());
        assertNothingChanged(result);
    }

    @Test
    void aFileWithNoActiveDomainAsksNobody() throws Exception {
        Result result = ensureDomainClients(domainsFile("[{\"domainId\":\"yy-old\",\"name\":\"Old\",\"active\":false},"
                + "{\"domainId\":\"yy-unset\",\"name\":\"No flag\"}]"), Map.of());

        assertEquals(0, result.status(), result.toString());
        assertTrue(result.output().contains("ensure_domain_clients returned"), result.toString());
        assertTrue(result.calls().isEmpty(), "no docker call: " + result);
    }

    /** Writes the domains.json of a country's bootstrap folder */
    Path domainsFile(String json) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("country-bootstrap"));
        return Files.writeString(folder.resolve("domains.json"), json, StandardCharsets.UTF_8);
    }

    /**
     * The first lines of a driver that is given the stand-ins' folder as $2: the stand-ins first on the PATH inside
     * bash, since Git's bin/bash.exe puts its own folders before the PATH it is given, and the run stops unless docker
     * (and, when given, curl) is the stand-in, so no real niord-keycloak or Keycloak is ever asked.
     */
    static String standIns(String... commands) {
        StringBuilder sh = new StringBuilder("set -euo pipefail\n"
                + "if command -v cygpath > /dev/null 2>&1; then stubs=\"$(cygpath -u \"$2\")\"; else stubs=\"$2\"; fi\n"
                + "PATH=\"$stubs:$PATH\"\n");
        for (String command : commands) {
            sh.append("[ \"$(command -v ").append(command).append(")\" = \"$stubs/").append(command).append("\" ] \\\n")
                    .append("    || { echo \"").append(command).append(" is $(command -v ").append(command)
                    .append("), not the stand-in\"; exit 99; }\n");
        }
        return sh.toString();
    }

    /** Sources the library and runs ensure_domain_clients on the domains file */
    Result ensureDomainClients(Path domains, Map<String, String> env) throws Exception {
        Path driver = dir.resolve("clients.sh");
        Files.writeString(driver, standIns("docker") + "source \"$1\"\nensure_domain_clients \"$3\"\n"
                + "echo \"ensure_domain_clients returned\"\n", StandardCharsets.UTF_8);
        return run(List.of(slashes(driver), slashes(LIBRARY), slashes(shims), slashes(domains)), env);
    }

    /** Sources the library and runs bootstrap_database on the archives, against the curl stand-in, which fails */
    Result bootstrapDatabase(List<Path> archives) throws Exception {
        Path driver = dir.resolve("bootstrap.sh");
        Files.writeString(driver, standIns("docker", "curl")
                + "source \"$1\"\n"
                + "shift 2\n"
                + "bootstrap_database http://127.0.0.1:9 niord_xx 'recreate it' log work \"$@\"\n"
                + "echo 'bootstrap_database returned'\n", StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of(slashes(driver), slashes(LIBRARY), slashes(shims)));
        archives.forEach(archive -> args.add(slashes(archive)));
        Files.deleteIfExists(dockerCalls);
        return run(args, Map.of());
    }

    static void assertBootstrapReturned(Result result) {
        assertEquals(0, result.status(), result.toString());
        assertTrue(result.output().contains("No sysadmin token"), "the token from the stand-in fails: " + result);
        assertTrue(result.output().contains("bootstrap_database returned"), result.toString());
    }

    /** The kcadm calls in niord-keycloak, each run with MSYS_NO_PATHCONV=1, without the docker exec in front */
    static List<String> kcadmCalls(Result result) {
        String prefix = "exec niord-keycloak /opt/keycloak/bin/kcadm.sh ";
        String suffix = " MSYS_NO_PATHCONV=1 COMPOSE_PROJECT_NAME=other";
        List<String> calls = new ArrayList<>();
        for (String call : result.calls()) {
            if (call.startsWith(prefix)) {
                assertTrue(call.endsWith(suffix), "kcadm runs with MSYS_NO_PATHCONV=1: " + call);
                calls.add(call.substring(prefix.length(), call.length() - suffix.length()));
            }
        }
        return calls;
    }

    /** No kcadm call changes or deletes anything in Keycloak */
    static void assertNothingChanged(Result result) {
        for (String call : kcadmCalls(result)) {
            assertFalse(call.matches("^(update|delete|remove-roles|set-password|set-new-password)\\b.*"),
                    "nothing in Keycloak is changed or deleted: " + call);
        }
    }

    /** Sources the library and runs sample_reset on the database */
    Result sampleReset(String db) throws Exception {
        Path driver = dir.resolve("reset.sh");
        Files.writeString(driver, "set -euo pipefail\nsource \"$1\"\nsample_reset \"$2\"\n", StandardCharsets.UTF_8);
        return run(List.of(slashes(driver), slashes(LIBRARY), db), Map.of());
    }

    /** Sources the library and runs start_services on the repository's compose file and realm */
    Result startServices(Map<String, String> env) throws Exception {
        // The driver goes to bash as a file, as Windows passes no argument with a quote in it whole
        Path driver = dir.resolve("services.sh");
        Files.writeString(driver, "set -euo pipefail\nsource \"$1\"\nstart_services \"$2\" \"$3\"\n",
                StandardCharsets.UTF_8);
        return run(List.of(slashes(driver), slashes(LIBRARY), slashes(COMPOSE), slashes(REALM)), env);
    }

    Result run(List<String> args, Map<String, String> extra) throws Exception {
        String bash = LoadSampleScriptTest.bash();
        assumeTrue(bash != null, "no bash on this machine (on Windows, Git Bash)");
        List<String> command = new ArrayList<>(List.of(bash));
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", shims.toAbsolutePath() + File.pathSeparator + env.getOrDefault("PATH", ""));
        env.put("HOME", slashes(home));
        env.put("USERPROFILE", home.toAbsolutePath().toString());
        env.put("COMPOSE_PROJECT_NAME", "other");
        env.remove("WINDOWS");
        env.remove("MSYS_NO_PATHCONV");
        env.putAll(extra);
        Process process = pb.start();
        process.getOutputStream().close();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the run did not end");
        List<String> calls = Files.exists(dockerCalls)
                ? Files.readAllLines(dockerCalls, StandardCharsets.UTF_8).stream().map(String::strip).toList()
                : List.of();
        return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8), calls);
    }

    static List<String> composeCalls(Result result) {
        return result.calls().stream().filter(call -> call.startsWith("compose ")).toList();
    }

    static String slashes(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
