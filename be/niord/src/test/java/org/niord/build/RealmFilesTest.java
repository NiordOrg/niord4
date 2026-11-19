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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The realm files the base product ships. The chart's realm ({@code be/k8s/helm/niord/files/niord-realm.json}, which a
 * dev-mode Keycloak imports and production mode derives its realm from) is the integration tests' realm byte for
 * byte, and its domain clients carry the domain ids of the base bootstrap and the sample: the backend reads a
 * domain's roles only from the client whose id is the domain's ({@code NiordDomainFilter}), so a client named
 * otherwise gives nobody a role in any domain. {@code be/k8s/scripts/deploy.sh} stops on a difference between the
 * two files instead of copying one over the other. Its run is skipped where no bash is found, as
 * {@link LoadSampleScriptTest} is.
 */
class RealmFilesTest {

    /** The tests run in the module's folder, be/niord */
    static final Path TEST_REALM = Path.of("src/test/resources/keycloak/niord-realm.json");
    static final Path CHART_REALM = Path.of("../k8s/helm/niord/files/niord-realm.json");
    static final Path DEPLOY = Path.of("../k8s/scripts/deploy.sh");
    static final Path BOOTSTRAP_DOMAINS = Path.of("bootstrap/domains.json");
    static final Path SAMPLE_DOMAINS = Path.of("sample/archive/domains.json");

    /** The clients of the realm that are no domain: Keycloak's own, and the two Niord clients that sign in */
    static final Set<String> NOT_DOMAINS = Set.of("account", "account-console", "admin-cli", "broker",
            "realm-management", "security-admin-console", "niord-web", "niord-web-admin");

    static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void theChartsRealmIsTheIntegrationTestsRealm() throws Exception {
        assertArrayEquals(Files.readAllBytes(TEST_REALM), Files.readAllBytes(CHART_REALM),
                CHART_REALM + " differs from " + TEST_REALM + ": they are one realm, and deploy.sh refuses a difference");
    }

    @Test
    void everyDomainClientIsNamedAfterADomain() throws Exception {
        Set<String> domains = new TreeSet<>(domainIds(BOOTSTRAP_DOMAINS));
        domains.addAll(domainIds(SAMPLE_DOMAINS));
        for (Path realm : List.of(TEST_REALM, CHART_REALM)) {
            Set<String> clients = new TreeSet<>();
            for (Map<String, Object> client : list(read(realm).get("clients"))) {
                clients.add((String) client.get("clientId"));
            }
            clients.removeAll(NOT_DOMAINS);
            assertEquals(domains, clients, realm + ": a client per domain of the base bootstrap and the sample, "
                    + "named with the domain's id, and no other");
        }
    }

    /** A difference stops deploy.sh before any helm or kubectl call, and neither file is changed */
    @Test
    void deployRefusesARealmThatDiffersFromTheTestRealm() throws Exception {
        String bash = LoadSampleScriptTest.bash();
        assumeTrue(bash != null, "no bash on this machine (on Windows, Git Bash)");

        // deploy.sh finds the repository's be/ folder two levels above itself
        Path script = Files.createDirectories(dir.resolve("k8s/scripts")).resolve("deploy.sh");
        Files.copy(DEPLOY, script);
        Path testRealm = Files.createDirectories(dir.resolve("niord/src/test/resources/keycloak"))
                .resolve("niord-realm.json");
        Path chartRealm = Files.createDirectories(dir.resolve("k8s/helm/niord/files")).resolve("niord-realm.json");
        Files.writeString(testRealm, "{\"realm\": \"niord\", \"clients\": [{\"clientId\": \"niord-nw\"}]}\n");
        Files.writeString(chartRealm, "{\"realm\": \"niord\", \"clients\": [{\"clientId\": \"niord-client-nw\"}]}\n");

        Path shims = Files.createDirectories(dir.resolve("bin"));
        Path calls = dir.resolve("calls");
        String callsPath = calls.toAbsolutePath().toString().replace('\\', '/');
        for (String tool : List.of("helm", "kubectl")) {
            Path shim = shims.resolve(tool);
            Files.writeString(shim, "#!/bin/sh\necho \"" + tool + " $*\" >> '" + callsPath + "'\nexit 1\n",
                    StandardCharsets.UTF_8);
            shim.toFile().setExecutable(true);
        }

        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(List.of(bash, script.toString(), "--no-wait")))
                .redirectErrorStream(true);
        pb.environment().put("PATH", shims.toAbsolutePath() + File.pathSeparator
                + pb.environment().getOrDefault("PATH", ""));
        pb.environment().remove("EXTRA_HELM_ARGS");
        Process process = pb.start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "deploy.sh did not end");

        assertEquals(1, process.exitValue(), output);
        assertTrue(output.contains("the chart's realm differs from the integration tests' realm"), output);
        assertTrue(Files.notExists(calls), "stopped before any helm or kubectl call: " + output);
        assertTrue(Files.readString(chartRealm).contains("niord-client-nw"), "the chart's realm is not overwritten");
        assertTrue(Files.readString(testRealm).contains("\"niord-nw\""), "the test realm is not overwritten");
    }

    static Map<String, Object> read(Path file) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), Map.class);
        return map;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    static Set<String> domainIds(Path file) throws Exception {
        List<?> domains = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), List.class);
        Set<String> ids = new TreeSet<>();
        for (Object domain : domains) {
            ids.add((String) ((Map<?, ?>) domain).get("domainId"));
        }
        return ids;
    }
}
