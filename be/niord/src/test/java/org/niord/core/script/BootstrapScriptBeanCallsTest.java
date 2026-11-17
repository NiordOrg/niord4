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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every method a base product's script calls on a bean it looks up exists (SCRIPT12): in each {@code *.js} of
 * {@code bootstrap/templates/}, a variable assigned {@code CdiUtils.getBean(<class>.class)} is followed to each
 * {@code <variable>.<method>(} call, and the class must have a public method of that name. A script is compiled only
 * when it runs, so a call of a method no class has fails only when a deployment's own script calls the function that
 * holds it. The release folders are past imports, kept as they were, and are not read.
 */
class BootstrapScriptBeanCallsTest {

    private static final Pattern BEAN = Pattern.compile(
            "\\bvar\\s+(\\w+)\\s*=\\s*CdiUtils\\.getBean\\(\\s*([\\w.$]+)\\.class\\s*\\)");

    @Test
    void everyMethodTheBaseScriptsCallOnALookedUpBeanExists() {
        Map<String, String> scripts = new LinkedHashMap<>();
        BootstrapTemplates.read(BootstrapTemplates.FOLDER).forEach((path, content) -> {
            if (path.endsWith(".js")) {
                scripts.put(path, content);
            }
        });
        assertTrue(scripts.containsKey("templates/tmpl/common.js"), "the base scripts are read: " + scripts.keySet());

        List<String> problems = new ArrayList<>();
        scripts.forEach((path, content) -> problems.addAll(check(path, content)));

        assertEquals(List.of(), problems, "base scripts that call a method the looked-up bean's class does not have");
    }

    @Test
    void aMissingMethodOrClassIsAProblem() {
        String lookup = "var m = CdiUtils.getBean(org.niord.core.message.MessageService.class);\n";
        assertEquals(List.of(), check("ok.js", lookup + "m.adjustMessage(message, AdjustmentType.TITLE);"));
        assertEquals(List.of("missing.js: org.niord.core.message.MessageService has no public method noSuchMethod"),
                check("missing.js", lookup + "function f() { m.noSuchMethod(message); }"));
        assertEquals(List.of("class.js: no class org.niord.core.message.NoSuchService"),
                check("class.js", "var s = CdiUtils.getBean(org.niord.core.message.NoSuchService.class);"));
    }

    /** The calls of one script on the beans it looks up that do not resolve, each naming the script */
    static List<String> check(String name, String content) {
        List<String> problems = new ArrayList<>();
        Matcher bean = BEAN.matcher(content);
        while (bean.find()) {
            String variable = bean.group(1);
            String className = bean.group(2);
            Class<?> type;
            try {
                type = Class.forName(className, false, BootstrapScriptBeanCallsTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                problems.add(name + ": no class " + className);
                continue;
            }
            Matcher call = Pattern.compile("(?<![\\w.$])" + Pattern.quote(variable) + "\\.(\\w+)\\s*\\(")
                    .matcher(content);
            while (call.find()) {
                String method = call.group(1);
                boolean exists = Arrays.stream(type.getMethods())
                        .map(Method::getName)
                        .anyMatch(method::equals);
                if (!exists) {
                    problems.add(name + ": " + className + " has no public method " + method);
                }
            }
        }
        return problems;
    }
}
