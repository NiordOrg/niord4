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
package org.niord.core.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one invariant that keeps log correlation honest as the codebase grows:
 * <b>every place that starts its own thread of execution must account for the MDC.</b>
 *
 * <p>Spring's {@code TaskDecorator} ({@code CorrelationConfig}) covers the
 * {@code applicationTaskExecutor} and the {@code taskScheduler}, and nothing else. Work handed to a
 * raw {@code java.util.concurrent} pool, or scheduled on a {@code java.util.Timer}, silently escapes
 * it — the task simply runs with an empty MDC, and the log lines it writes name no request. Nothing
 * fails; the correlation just quietly stops being true, which is the worst failure mode an
 * observability feature can have.
 *
 * <p>This test is a static check rather than a behavioural one because that is what the failure mode
 * demands: the bug is a call site nobody remembered, so the guard has to be over call sites. It was
 * added after three {@code java.util.Timer} sites were missed on the first pass — including
 * {@code BatchSetService}, which schedules delayed batch-set items straight from a request thread.
 *
 * <p><b>Satisfying this test does not require wrapping.</b> A file passes if it mentions
 * {@link MdcContext} at all, so the two honest outcomes both work:
 * <ul>
 *   <li>wrap the task — {@link MdcContext#wrap}, {@link MdcContext#wrapRunnable},
 *       {@link MdcContext#wrapAll} to inherit a caller's context, or
 *       {@link MdcContext#withFreshJobId} to mint one for work that has no caller; or</li>
 *   <li>record why it is excluded, in a comment naming {@code MdcContext} — as the poll-loop
 *       listeners do, since a long-lived receive loop has no caller context to inherit and needs an
 *       id per received message instead.</li>
 * </ul>
 * What it forbids is doing neither silently.
 */
public class MdcCoverageTest {

    /** Surefire runs with the module directory as its working directory. */
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /**
     * Execution contexts Spring does not manage. Deliberately narrow: it matches the <em>creation</em>
     * of a timer or a pool (through {@code Executors} or a pool's own constructor), not every
     * {@code submit}/{@code execute} call, so it flags the handful of places that own a thread rather
     * than every use of one.
     */
    private static final Pattern OWNS_A_THREAD = Pattern.compile(
            "new Timer\\(|new java\\.util\\.Timer\\(|Executors\\.new|new (Scheduled)?ThreadPoolExecutor\\(");

    @Test
    void everyUnmanagedExecutionSiteAccountsForTheCorrelationContext() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCES),
                "expected to scan " + MAIN_SOURCES.toAbsolutePath() + " — has the module layout changed?");

        List<String> scanned = new ArrayList<>();
        List<String> unaccounted = new ArrayList<>();

        try (Stream<Path> tree = Files.walk(MAIN_SOURCES)) {
            List<Path> sources = tree.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : sources) {
                String source = Files.readString(file);
                if (!OWNS_A_THREAD.matcher(source).find()) {
                    continue;
                }
                String name = MAIN_SOURCES.relativize(file).toString();
                scanned.add(name);
                if (!source.contains("MdcContext")) {
                    unaccounted.add(name);
                }
            }
        }

        // Without this, a wrong path or a broken pattern would scan nothing and pass silently —
        // the same "green because it did no work" trap this test exists to prevent elsewhere.
        assertTrue(!scanned.isEmpty(),
                "found no unmanaged execution sites at all, so this test proved nothing; "
                        + "the scan path or the pattern is wrong");

        assertTrue(unaccounted.isEmpty(),
                "these files start their own thread of execution but never mention MdcContext, so any "
                        + "log line they produce silently loses its request/job id:\n  "
                        + String.join("\n  ", unaccounted)
                        + "\n\nEither wrap the task (MdcContext.wrap / wrapRunnable / wrapAll to inherit a "
                        + "caller's context, withFreshJobId for work that has none), or add a comment "
                        + "naming MdcContext that says why this site is excluded.");
    }
}
