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

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The condition-blind half of the endpoint table's completeness: every handler the classpath scan finds (the
 * conditional SECOM administration interface included) has exactly one entry with every one of its paths, its name
 * and its annotation; every entry names a handler that exists, at paths the handler declares; and every entry has a
 * case, a covering test or an exemption. The running half, against the application's handler mapping, is
 * {@link EndpointTableCompletenessITTest}.
 *
 * <p>With {@code -Dniord.endpoints.write=true} it first runs {@link EndpointTableGenerator#write} on the source folder
 * ({@code src/test/resources/endpoints}): skeleton entries for the handlers without one, the generated fields
 * refreshed, no path removed, no decision touched. The checks then read the source folder.
 */
class EndpointTableCompletenessTest {

    @Test
    void everyHandlerHasOneEntryAndEveryEntryAHandler() throws Exception {
        boolean writing = Boolean.getBoolean(EndpointTableGenerator.WRITE_PROPERTY);
        if (writing) {
            List<String> changes = EndpointTableGenerator.write(EndpointTable.SOURCE_FOLDER);
            System.out.println("EndpointTableGenerator: " + changes.size() + " change(s)\n  "
                    + String.join("\n  ", changes));
        }
        EndpointTable table = Files.isDirectory(EndpointTable.SOURCE_FOLDER)
                ? EndpointTable.load(EndpointTable.SOURCE_FOLDER) : EndpointTable.load();
        List<EndpointTableGenerator.Handler> handlers = EndpointTableGenerator.scan();

        // Guard the guard: a partial scan must not pass
        assertTrue(handlers.size() > 300, "sanity: the scan found only " + handlers.size() + " handlers");
        assertTrue(handlers.stream().anyMatch(h -> h.controller().getSimpleName().equals("SecomAdminRestService")),
                "sanity: the scan is condition-blind and finds the SECOM administration interface");

        assertEquals(List.of(), problems(table, handlers), "The endpoint table does not match the handlers");
    }

    /** What is wrong between the table and the scanned handlers, one sentence each */
    static List<String> problems(EndpointTable table, List<EndpointTableGenerator.Handler> handlers) {
        List<String> problems = new ArrayList<>();
        Set<EndpointEntry> matched = new HashSet<>();
        for (EndpointTableGenerator.Handler handler : handlers) {
            List<EndpointEntry> entries = new ArrayList<>();
            for (String path : handler.paths()) {
                EndpointEntry entry = table.entryAt(handler.verb(), path);
                if (entry != null && !entries.contains(entry)) {
                    entries.add(entry);
                }
            }
            if (entries.isEmpty()) {
                problems.add(missing(handler, table));
                continue;
            }
            if (entries.size() > 1) {
                problems.add(handler.key() + " (" + handler.name() + ") is listed by " + entries.size()
                        + " entries: " + entries + ". One handler has one entry with every path.");
            }
            EndpointEntry entry = entries.getFirst();
            matched.add(entry);
            for (String path : handler.paths()) {
                if (!entry.paths().contains(path)) {
                    problems.add(handler.verb() + " " + path + " (" + handler.name() + ") is not listed by its entry "
                            + entry.key + ". Run EndpointTableGenerator (-D" + EndpointTableGenerator.WRITE_PROPERTY
                            + "=true) to add it to alsoAt.");
                }
            }
            if (!entry.handler.equals(handler.name())) {
                problems.add(entry.key + " names the handler " + entry.handler + "; the path is mapped to "
                        + handler.name() + ".");
            }
            if (!handler.guard().equals(entry.guard)) {
                problems.add(entry.key + " is now " + handler.guard() + "; the table says " + entry.guard
                        + ". Decide its cells again, then run EndpointTableGenerator to refresh the guard.");
            }
            for (String path : entry.paths()) {
                if (!handler.paths().contains(path)) {
                    problems.add(entry.verb() + " " + path + " is listed for " + entry.handler + " but resolves to no "
                            + "handler. The frontend may still call it; remove it from the entry only if no caller "
                            + "uses it.");
                }
            }
        }
        for (EndpointEntry entry : table.entries()) {
            if (!matched.contains(entry)) {
                problems.add(entry.key + " (" + entry.handler + ") names no handler that exists. Remove the entry if "
                        + "the handler is gone for good, or correct its key.");
            }
            if (entry.caseName == null && entry.coveredBy == null && entry.exempt == null) {
                problems.add(entry.key + " has no case, no coveredBy and no exempt.");
            }
        }
        return problems;
    }

    private static String missing(EndpointTableGenerator.Handler handler, EndpointTable table) {
        String group = table.groupOfController(handler.controller().getSimpleName());
        return handler.key() + " (" + handler.name() + ") has no entry in be/niord/src/test/resources/endpoints/. Run "
                + "EndpointTableGenerator (-D" + EndpointTableGenerator.WRITE_PROPERTY + "=true) to add a skeleton "
                + "entry to " + (group == null ? "the file of its controller's group (add the controller to that "
                + "file's controllers first)" : group + ".json")
                + ", decide its cells, add a case to the group's SweepGroup, and set reviewed to true.";
    }
}
