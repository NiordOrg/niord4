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
package org.niord.web.sweep;

import org.junit.jupiter.api.Test;
import org.niord.core.conf.CountryContractGuard;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The running half of the endpoint table's completeness: every handler the application's
 * {@code RequestMappingHandlerMapping} maps (as {@code RestHandlerSecurityITTest} walks it) has an entry that lists
 * the verb and the pattern and names the handler; a framework handler is allowed only when
 * {@link CountryContractGuard#REVIEWED_FRAMEWORK_HANDLERS} names it; every entry of the main sweep names a handler the
 * application maps; and every path an entry lists, filled with sample values, resolves through the handler mapping to
 * that entry's handler, so a path that no longer reaches its handler fails here even when the frontend still calls
 * it. The condition-blind half is {@link EndpointTableCompletenessTest}.
 */
class EndpointTableCompletenessITTest extends ITTest {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void everyMappedHandlerHasItsEntryAndEveryListedPathReachesItsHandler() {
        EndpointTable table = EndpointTable.load();
        List<RequestMappingHandlerMapping> mappings =
                new ArrayList<>(applicationContext.getBeansOfType(RequestMappingHandlerMapping.class).values());
        List<String> problems = new ArrayList<>();
        Set<String> frameworkTypes = new TreeSet<>();
        Map<String, List<String>> consumesByKey = new java.util.HashMap<>();
        Set<String> mapped = new TreeSet<>();
        int handlers = 0;

        for (RequestMappingHandlerMapping mapping : mappings) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
                HandlerMethod handler = e.getValue();
                if (!handler.getBeanType().getName().startsWith("org.niord.")) {
                    frameworkTypes.add(handler.getBeanType().getName());
                    continue;
                }
                handlers++;
                String name = handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName();
                RequestMappingInfo info = e.getKey();
                List<String> consumes = info.getConsumesCondition().getConsumableMediaTypes().stream()
                        .map(MediaType::toString).toList();
                for (RequestMethod verb : info.getMethodsCondition().getMethods()) {
                    for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                        EndpointEntry entry = table.entryAt(verb.name(), pattern);
                        if (entry == null) {
                            problems.add(verb + " " + pattern + " (" + name + ") has no entry in "
                                    + "be/niord/src/test/resources/endpoints/. Run EndpointTableGenerator (-D"
                                    + EndpointTableGenerator.WRITE_PROPERTY + "=true) to add a skeleton entry to the "
                                    + "file of its controller's group, decide its cells, add a case to the group's "
                                    + "SweepGroup, and set reviewed to true.");
                        } else if (!entry.handler.equals(name)) {
                            problems.add(verb + " " + pattern + " is mapped to " + name + "; its entry " + entry.key
                                    + " names " + entry.handler + ".");
                        } else {
                            mapped.add(entry.key);
                            consumesByKey.put(entry.key, consumes);
                        }
                    }
                }
                if (info.getMethodsCondition().getMethods().isEmpty()) {
                    problems.add(name + " maps every verb; the table needs one verb per entry.");
                }
            }
        }

        for (EndpointEntry entry : table.entries()) {
            if (entry.context != null) {
                continue;
            }
            if (!mapped.contains(entry.key)) {
                problems.add(entry.key + " (" + entry.handler + ") is in the main sweep, but the running application "
                        + "maps no such handler there.");
                continue;
            }
            for (String path : entry.paths()) {
                String problem = resolution(mappings, table, entry, path, consumesByKey.get(entry.key));
                if (problem != null) {
                    problems.add(entry.verb() + " " + path + " is listed for " + entry.handler + " but resolves to "
                            + "no handler: " + problem + ". The frontend may still call it; remove it from the entry "
                            + "only if no caller uses it.");
                }
            }
        }

        // Guard the guard: a context without the REST layer must not pass
        assertTrue(handlers > 300, "sanity: the handler mapping maps only " + handlers + " niord handlers");
        assertEquals(List.of(), problems, "The endpoint table does not match the running application's handlers");
        assertEquals(new TreeSet<>(CountryContractGuard.REVIEWED_FRAMEWORK_HANDLERS), frameworkTypes,
                "A framework handler is exempt from the table only when CountryContractGuard.REVIEWED_FRAMEWORK_HANDLERS "
                        + "names it");
    }

    /** Null when the path, filled with a sample value, reaches the entry's handler; else what it reaches */
    private static String resolution(List<RequestMappingHandlerMapping> mappings, EndpointTable table,
                                     EndpointEntry entry, String path, List<String> consumes) {
        String first = null;
        for (String value : List.of("1", "a", "sweep.json")) {
            SweepRequest request = SweepRequest.sample(entry.verb(), path, value);
            if (consumes != null && !consumes.isEmpty()) {
                if (consumes.stream().anyMatch(c -> c.startsWith("multipart"))) {
                    request.file("file", "sweep.json", new byte[0], "application/json");
                } else {
                    request.body(new byte[0], consumes.getFirst().replace("*", "x"));
                }
            }
            String problem = SweepEngine.resolve(mappings, table, request);
            if (problem == null) {
                return null;
            }
            if (first == null) {
                first = problem;
            }
        }
        return first;
    }
}
