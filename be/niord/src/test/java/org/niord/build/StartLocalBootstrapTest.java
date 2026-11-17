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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a launcher says when {@code bootstrap.sh} reports that the bootstrap it posted did not finish, by the marker read
 * afterwards ({@code bootstrap_outcome}): pending, refused before it started, the remedy named for a database that
 * holds data; applied, finished after {@code bootstrap.sh} gave up waiting, so that {@code --sample} goes on to load;
 * failed and in-progress, the remedy named. The function belongs to the launcher library, be/scripts/local-run-lib.sh,
 * which the repository root's {@code start-local.sh} and a country's launcher source. The whole library is sourced, so
 * the test also shows that sourcing it runs nothing. Skipped where no bash is found, as {@link LoadSampleScriptTest} is.
 */
class StartLocalBootstrapTest {

    /** The launcher library; the tests run in the module's folder, be/niord */
    static final Path SCRIPT = Path.of("../scripts/local-run-lib.sh");

    static final String RECREATE = "RECREATE IT";

    @TempDir
    Path dir;

    /** What the function printed, standard error included, and the state it left */
    record Result(int status, String output, String state) {
        @Override
        public String toString() {
            return "exit " + status + ", BOOTSTRAP_STATE '" + state + "'\n" + output;
        }
    }

    @Test
    void aPendingMarkerSaysTheBootstrapWasRefusedBeforeItStarted() throws Exception {
        Result result = outcome("pending");

        assertEquals(0, result.status(), result.toString());
        assertTrue(result.output().contains("refused before it started (the reason above)")
                && result.output().contains("for a database that holds data, " + RECREATE), result.toString());
        assertFalse(result.output().contains("did not finish"), result.toString());
        assertEquals("", result.state(), "nothing is bootstrapped: " + result);
    }

    @Test
    void anAppliedMarkerSaysTheRunFinishedAndLetsTheSampleLoad() throws Exception {
        Result result = outcome("applied");

        assertEquals(0, result.status(), result.toString());
        assertTrue(result.output().contains("finished after bootstrap.sh gave up waiting for it")
                && result.output().contains("database niord_it reads applied"), result.toString());
        assertEquals("applied", result.state(), "--sample loads on applied: " + result);
    }

    @Test
    void aFailedOrInProgressMarkerNamesTheRemedy() throws Exception {
        for (String state : List.of("failed", "in-progress")) {
            Result result = outcome(state);
            assertEquals(0, result.status(), result.toString());
            assertTrue(result.output().contains(RECREATE), result.toString());
            assertEquals("", result.state(), result.toString());
        }
        Result unread = outcome("");
        assertTrue(unread.output().contains("The bootstrap did not finish (see above and it.log)"), unread.toString());
        assertEquals("", unread.state(), unread.toString());
    }

    /** Sources the library and calls bootstrap_outcome with the marker's state */
    Result outcome(String state) throws Exception {
        String bash = LoadSampleScriptTest.bash();
        assumeTrue(bash != null, "no bash on this machine (on Windows, Git Bash)");
        String body = "source \"$1\" || exit 90\n"
                + "BOOTSTRAP_STATE=\n"
                + "bootstrap_outcome \"$2\" niord_it \"$3\" it.log\n"
                + "rc=$?\n"
                + "echo \"STATE=[$BOOTSTRAP_STATE]\"\n"
                + "exit $rc\n";
        // The driver goes to bash as a file, as Windows passes no argument with a quote in it whole; the state as
        // an environment variable, which may be empty
        Path driver = dir.resolve("outcome.sh");
        Files.writeString(driver, body.replace("\"$2\"", "\"$OUTCOME_STATE\""), StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(bash, driver.toAbsolutePath().toString().replace('\\', '/'),
                SCRIPT.toString().replace('\\', '/'), "", RECREATE).redirectErrorStream(true);
        pb.environment().put("OUTCOME_STATE", state);
        Process process = pb.start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the function did not end");
        int at = output.lastIndexOf("STATE=[");
        String left = at < 0 ? null : output.substring(at + 7, output.indexOf(']', at));
        return new Result(process.exitValue(), at < 0 ? output : output.substring(0, at), left);
    }
}
