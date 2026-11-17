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
package org.niord.core.batch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link BatchService#resolveDataFile}: a batch data file lies directly inside its job's execution folder.
 * Below the batch-job root is not enough: the sibling {@code in} folders start the job they are named after
 * with whatever file lands in them.
 */
class BatchServiceDataFileTest {

    /** An execution folder as BatchService computes it: [root]/[jobName]/execution/[year]/[month]/[jobNo] */
    private static final Path JOB_FOLDER = Paths.get("batch-jobs", "chart-import", "execution", "2026", "9", "7")
            .toAbsolutePath().normalize();

    @Test
    void aBareNameResolvesDirectlyInsideTheJobFolder() {
        assertEquals(JOB_FOLDER.resolve("charts.json"), BatchService.resolveDataFile(JOB_FOLDER, "charts.json"));
        assertEquals(JOB_FOLDER.resolve("Søkort (2).json"), BatchService.resolveDataFile(JOB_FOLDER, "Søkort (2).json"));
    }

    @Test
    void aNameThatClimbsOutOfTheJobFolderIsRefused() {
        for (String name : new String[] {
                "../../../../other-job/in/x.json",
                "../x.json",
                "sub/../../x.json" }) {
            assertThrows(IllegalArgumentException.class, () -> BatchService.resolveDataFile(JOB_FOLDER, name),
                    "'" + name + "' must be refused");
        }
    }

    @Test
    void aNameInASubFolderOrInAbsoluteFormIsRefused() {
        String absolute = JOB_FOLDER.getRoot().resolve("x.json").toString();
        for (String name : new String[] { "sub/x.json", absolute, "", ".", ".." }) {
            assertThrows(IllegalArgumentException.class, () -> BatchService.resolveDataFile(JOB_FOLDER, name),
                    "'" + name + "' must be refused");
        }
    }

    @Test
    void aMissingNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BatchService.resolveDataFile(JOB_FOLDER, null));
    }
}
