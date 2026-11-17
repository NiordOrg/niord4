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
package org.niord.core.script;

import org.junit.jupiter.api.Test;
import org.niord.core.script.vo.ScriptResourceVo;
import org.niord.core.util.JsonUtils;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The script check of the import and the bootstrap (docs/code-data-config-plan.md §6.5, {@link ScriptResourceCheck}):
 * one failing fixture for each of its script rules and path rules, the fixtures it accepts, the template check, and
 * the base product's own files, which all pass: every file of {@code bootstrap/templates/} and every entry of every
 * release file.
 */
class ScriptResourceCheckTest {

    private static final Path BOOTSTRAP = Path.of("bootstrap");

    /** A script for each rule, in the order of {@link ScriptResourceCheck#SCRIPT_RULES} */
    private static final List<String> RULE_FIXTURES = List.of(
            "var s = Packages.java.lang.System;",
            "var r = Java.type('java.lang.Runtime');",
            "var T = Java.extend(java.util.TimerTask, {});",
            "var f = new java.io.File('/etc/passwd');",
            "var z = new java.util.zip.ZipFile('/etc/x.zip');",
            "var t = org.apache.commons.io.FileUtils.readFileToString(f);",
            "java.lang.Runtime.getRuntime().exec('ls');",
            "new ProcessBuilder('ls').start();",
            "var m = new javax.script.ScriptEngineManager();",
            "var c = jdk.nashorn.api.scripting.ScriptUtils;",
            "var k = message.getClass();",
            "eval('1 + 1');",
            "var f = new Function('return 1');",
            "load('http://x');",
            "em.persist(message);",
            "query.executeUpdate();",
            "var u = CdiUtils.getBean(org.niord.core.user.UserService.class);");

    @Test
    void eachScriptRuleRefusesItsFixture() {
        assertEquals(ScriptResourceCheck.SCRIPT_RULES.size(), RULE_FIXTURES.size(), "one fixture for each rule");
        for (int i = 0; i < RULE_FIXTURES.size(); i++) {
            String reason = ScriptResourceCheck.SCRIPT_RULES.get(i).reason();
            List<String> reasons = ScriptResourceCheck.check("templates/tmpl/x.js", RULE_FIXTURES.get(i));
            assertTrue(reasons.stream().anyMatch(r -> r.endsWith(reason)),
                    "'" + RULE_FIXTURES.get(i) + "' breaks the rule '" + reason + "': " + reasons);
        }
    }

    @Test
    void eachRefusalNamesTheTokenFound() {
        assertRefused("var r = Java.type('java.lang.Runtime');", "Java.type('java.lang.Runtime')");
        assertRefused("var f = new java.io.File('/etc/passwd');", "java.io.File");
        assertRefused("var p = java.nio.file.Paths.get('/');", "java.nio.file.Paths.get('/')");
        assertRefused("var m = java.lang.reflect.Modifier;", "java.lang.reflect.Modifier");
        assertRefused("new ProcessBuilder('ls').start();", "ProcessBuilder");
        assertRefused("var k = message.getClass();", "getClass()");
        assertRefused("var c = Class.forName('x');", "forName('x')");
        assertRefused("eval('1 + 1');", "eval('1 + 1')");
        assertRefused("var f = new Function('return 1');", "Function('return 1')");
        assertRefused("load('http://x');", "load('http://x')");
        assertRefused("var p = 'niord:templates/tmpl/common.js'; load(p);", "load(p)");
        assertRefused("loadWithNewGlobal('http://x');", "loadWithNewGlobal('http://x')");
        assertRefused("em.persist(message);", "em.persist(message)");
        assertRefused("var u = CdiUtils.getBean(org.niord.core.user.UserService.class);",
                "CdiUtils.getBean(org.niord.core.user.UserService.class)");
        assertRefused("var t = 'org.niord.core.message.MessageService'; var s = CdiUtils.getBean(x);",
                "CdiUtils.getBean(x)");
    }

    /**
     * The two rules the second review of PR #179 asked for: within the allowed java.util., the sub-packages that reach
     * files, service loading and threads; and the org, com, net and edu roots the engine exposes, other than
     * niord-base's own packages
     */
    @Test
    void theJavaUtilSubPackagesAndTheOtherRootsAreRefused() {
        assertRefused("var z = new java.util.zip.ZipFile('/etc/x.zip');", "java.util.zip.ZipFile('/etc/x.zip')");
        assertRefused("var j = new java.util.jar.JarFile('/x.jar');", "java.util.jar.JarFile('/x.jar')");
        assertRefused("var h = new java.util.logging.FileHandler('/tmp/x');", "java.util.logging.FileHandler");
        assertRefused("var t = java.util.spi.ToolProvider.findFirst('javac');", "java.util.spi.ToolProvider");
        assertRefused("var e = java.util.concurrent.Executors.newFixedThreadPool(1);",
                "java.util.concurrent.Executors");
        assertRefused("var s = org.apache.commons.io.FileUtils.readFileToString(f);",
                "org.apache.commons.io.FileUtils.readFileToString(f)");
        assertRefused("var c = com.sun.net.httpserver.HttpServer;", "com.sun.net.httpserver.HttpServer");
        assertRefused("var n = net.sf.jasperreports.engine.JasperRunManager;", "net.sf.jasperreports");
        assertRefused("var w = org.niord.web.MessageRestService;", "org.niord.web.MessageRestService");
        assertRefused("var e = edu.example.X;", "edu.example.X");
    }

    /**
     * The check reads tokens, so it is no boundary: these forms reach the same classes and pass it. Asserted, so that
     * the limit is written down (the class Javadoc; be/docs/country-api.md, The script allow-list)
     */
    @Test
    void theKnownEvasionsPassTheCheck() {
        for (String script : List.of(
                "var r = Java[\"type\"](className);",
                "em[\"remove\"](message);",
                "var p = 'java.' + 'io.File'; var t = Java['ty' + 'pe'](p);")) {
            assertEquals(List.of(), ScriptResourceCheck.check("templates/tmpl/x.js", script),
                    "a known evasion, accepted: " + script);
        }
    }

    @Test
    void whatTheBaseProductAndTheCountriesUseIsAccepted() {
        for (String script : List.of(
                "var a = new java.util.ArrayList(); var d = new java.util.Date();",
                "var c = java.util.Calendar.getInstance(); var z = java.time.ZoneId.of('UTC');",
                "var f = new java.text.SimpleDateFormat('yyyy'); var n = new java.math.BigDecimal('1');",
                "load('niord:templates/tmpl/common.js');",
                "load(\"niord:templates/tmpl/common.js\");",
                "function adjust() { return 1; } var f = function (x) { return x; };",
                "org.niord.core.geojson.GeoJsonUtils.addAtonAffectedRadius(part.geometry, 'affected');",
                "var t = org.niord.model.message.Type.LOCAL_WARNING;",
                "var mail = 'niord@example.org'; var site = 'https://www.example.com/x.html';",
                "var x = Java.to([1, 2], 'double[]');",
                "// This file mentions FmTemplateService.java: a source file name is no package")) {
            assertEquals(List.of(), ScriptResourceCheck.check("templates/tmpl/x.js", script), script);
        }
        for (String type : ScriptResourceCheck.ALLOWED_BEAN_TYPES) {
            String script = "var s = CdiUtils.getBean(" + type + ".class);";
            assertEquals(List.of(), ScriptResourceCheck.check("templates/tmpl/x.js", script), script);
        }
    }

    @Test
    void eachPathRuleRefusesItsFixture() {
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("", "the path is blank");
        fixtures.put("   ", "the path is blank");
        fixtures.put("templates/" + "x".repeat(ScriptResourceCheck.MAX_PATH_LENGTH) + ".ftl",
                "longer than " + ScriptResourceCheck.MAX_PATH_LENGTH);
        fixtures.put("templates/../x.ftl", "'..' segment");
        fixtures.put("templates\\x.ftl", "backslash");
        fixtures.put("/templates/x.ftl", "starts with '/'");
        fixtures.put("templates/x.txt", "neither in .ftl nor in .js");
        fixtures.forEach((path, reason) -> {
            List<String> reasons = ScriptResourceCheck.check(path, "");
            assertTrue(reasons.stream().anyMatch(r -> r.contains(reason)), "'" + path + "': " + reasons);
        });
        assertEquals(List.of("the path is blank"), ScriptResourceCheck.check(null, ""));
        assertEquals(List.of(), ScriptResourceCheck.check("templates/x.ftl", null), "no content is an empty file");
    }

    @Test
    void aTemplateThatInstantiatesANonDirectiveOrDoesNotParseIsRefused() {
        List<String> reasons = ScriptResourceCheck.check("templates/x.ftl",
                "<#assign ex = \"freemarker.template.utility.Execute\"?new()>${ex(\"echo hi\")}");
        assertEquals(List.of("?new of freemarker.template.utility.Execute, which is not a directive"), reasons);

        assertTrue(ScriptResourceCheck.check("templates/x.ftl", "<#if>").get(0).startsWith("does not parse"));
        assertEquals(List.of("1 ?new not preceded by a string literal"),
                ScriptResourceCheck.check("templates/x.ftl", "<#assign c = 'x'><#assign ex = c?new()>"));
        assertEquals(List.of(), ScriptResourceCheck.check("templates/x.ftl",
                "<#assign l = 'org.niord.core.script.directive.LineDirective'?new()>"));
        assertEquals(List.of(), ScriptResourceCheck.check("templates/x.ftl",
                "<#include \"common.ftl\">${text('x')}"), "an include is not resolved by the check");
    }

    @Test
    void theBaseProductsBootstrapTemplatesAndReleaseFilesPass() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        BootstrapTemplates.read(BOOTSTRAP.resolve("templates")).forEach((path, content) ->
                files.put("bootstrap/" + path, content));
        long scripts = files.keySet().stream().filter(p -> p.endsWith(".js")).count();
        long templates = files.keySet().stream().filter(p -> p.endsWith(".ftl")).count();

        try (Stream<Path> folders = Files.list(BOOTSTRAP.resolve("releases"))) {
            for (Path folder : folders.filter(Files::isDirectory).sorted().toList()) {
                Path file = folder.resolve("script-resources.json");
                if (Files.isRegularFile(file)) {
                    for (ScriptResourceVo resource : JsonUtils.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                            new TypeReference<List<ScriptResourceVo>>() {})) {
                        files.put(folder.getFileName() + ": " + resource.getPath(), resource.getContent());
                    }
                }
            }
        }

        List<String> problems = new ArrayList<>();
        files.forEach((name, content) -> {
            String path = name.substring(name.indexOf("templates/"));
            ScriptResourceCheck.check(path, content).forEach(reason -> problems.add(name + ": " + reason));
        });
        assertEquals(List.of(), problems, "the base product's own files pass the check");
        assertEquals(80, scripts + templates, "the base product's 80 templates and scripts");
        assertTrue(scripts >= 4 && templates >= 70, scripts + " scripts and " + templates + " templates");
        assertTrue(files.size() >= 160, "the bootstrap's files and the first release's: " + files.size());
    }

    private static void assertRefused(String script, String token) {
        List<String> reasons = ScriptResourceCheck.check("templates/tmpl/x.js", script);
        assertTrue(reasons.stream().anyMatch(r -> r.contains(token)),
                "the refusal of '" + script + "' names '" + token + "': " + reasons);
    }
}
