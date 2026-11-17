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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every data template the base product ships parses, and instantiates only directives (docs/code-data-config-plan.md
 * §6.5, the second test): each {@code *.ftl} of {@code bootstrap/templates/} and each FreeMarker entry of every
 * release import file ({@code bootstrap/releases/<folder>/script-resources.json}) parses with the configuration every
 * template is rendered with ({@link FmTemplateService#newConfiguration}), and every literal class name before
 * {@code ?new} passes {@link DirectiveClassResolver#isDirective} and loads. A parse alone does not catch a disallowed
 * {@code ?new}: the resolver is consulted only when the {@code ?new} is evaluated, at render time, so a row that
 * named another class would stop rendering with no build to catch it. A {@code ?new} not preceded by a string literal
 * cannot be checked, and fails the test. The check is {@link ScriptResourceCheck#checkTemplate}, the one the import
 * and the bootstrap run. The countries' bootstraps join in C6.
 */
class BootstrapTemplatesParseTest {

    private static final Path BOOTSTRAP = Path.of("bootstrap");

    @Test
    void everyBootstrapTemplateParsesAndInstantiatesOnlyDirectives() throws IOException {
        Map<String, String> templates = dataTemplates();
        assertTrue(templates.size() > 50, "the base product's templates are read: " + templates.size());

        List<String> problems = new ArrayList<>();
        templates.forEach((name, content) -> problems.addAll(check(name, content)));

        assertEquals(List.of(), problems, "data templates that do not parse or instantiate a class outside the "
                + "directive package");
    }

    @Test
    void aNonDirectiveOrAnUncheckableNewIsAProblem() {
        assertEquals(List.of(), check("ok.ftl",
                "<#assign l = 'org.niord.core.script.directive.LineDirective'?new()>"));
        assertEquals(1, check("execute.ftl",
                "<#assign ex = \"freemarker.template.utility.Execute\"?new()>${ex(\"echo hi\")}").size());
        assertEquals(1, check("variable.ftl", "<#assign c = 'x'><#assign ex = c?new()>").size());
        assertEquals(1, check("missing.ftl",
                "<#assign m = 'org.niord.core.script.directive.NoSuchDirective'?new()>").size());
        assertEquals(1, check("broken.ftl", "<#if>").size());
    }

    /** The parse and the ?new check of one template; the problems found, each naming the template */
    static List<String> check(String name, String content) {
        return ScriptResourceCheck.checkTemplate(name, content).stream().map(reason -> name + ": " + reason).toList();
    }

    /** The FreeMarker templates of bootstrap/templates/ and of every release file, by a name saying where */
    private static Map<String, String> dataTemplates() throws IOException {
        Map<String, String> templates = new LinkedHashMap<>();
        BootstrapTemplates.read(BOOTSTRAP.resolve("templates")).forEach((path, content) -> {
            if (path.endsWith(".ftl")) {
                templates.put("bootstrap/" + path, content);
            }
        });
        Path releases = BOOTSTRAP.resolve("releases");
        if (Files.isDirectory(releases)) {
            try (Stream<Path> folders = Files.list(releases)) {
                for (Path folder : folders.filter(Files::isDirectory).sorted().toList()) {
                    Path file = folder.resolve("script-resources.json");
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    List<ScriptResourceVo> resources = JsonUtils.fromJson(
                            Files.readString(file, StandardCharsets.UTF_8),
                            new TypeReference<List<ScriptResourceVo>>() {});
                    for (ScriptResourceVo resource : resources) {
                        if (resource.getType() == ScriptResource.Type.FM) {
                            templates.put(BOOTSTRAP.relativize(file).toString().replace('\\', '/') + ": "
                                    + resource.getPath(), resource.getContent());
                        }
                    }
                }
            }
        }
        assertFalse(templates.isEmpty());
        return templates;
    }
}
