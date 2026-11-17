/*
 * Copyright 2016 Danish Maritime Authority.
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
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Framework-neutral unit tests for {@link BatchData}: the batch-job folder / data-file path layout and the
 * {@code @PrePersist} created-date defaulting. These are pure logic and survive a framework migration intact.
 */
public class BatchDataTest {

    // ------------------------------------------------------------------------------------------- //
    // computeBatchJobFolderPath: [jobName]/execution/[year]/[month(1-based)]/[jobNo]
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void folderPathFollowsJobNameExecutionYearMonthJobNoLayout() {
        // March is Calendar.MONTH == 2 (zero-based); the path uses the 1-based month, i.e. "3".
        BatchData job = BatchTestFactory.batchData("my-job", 42L, BatchTestFactory.dateOf(2020, java.util.Calendar.MARCH, 15));

        Path expected = Paths.get("my-job", "execution", "2020", "3", "42");
        assertEquals(expected, job.computeBatchJobFolderPath());
    }

    @Test
    public void folderPathUsesOneBasedMonthForJanuaryAndDecember() {
        BatchData jan = BatchTestFactory.batchData("j", 1L, BatchTestFactory.dateOf(2021, java.util.Calendar.JANUARY, 1));
        assertEquals(Paths.get("j", "execution", "2021", "1", "1"), jan.computeBatchJobFolderPath());

        BatchData dec = BatchTestFactory.batchData("j", 7L, BatchTestFactory.dateOf(2021, java.util.Calendar.DECEMBER, 31));
        assertEquals(Paths.get("j", "execution", "2021", "12", "7"), dec.computeBatchJobFolderPath());
    }

    @Test
    public void folderPathDefaultsCreatedWhenNullAndNeverReturnsNull() {
        // This method never returns null — see docs/potential-errors.md (B1). When created is null it
        // is defaulted to now, so the path is always computed. (The Javadoc previously carried a
        // copy-pasted "returns null if no data file is defined" sentence; that was corrected.)
        BatchData job = new BatchData();
        job.setJobName("late");
        job.setJobNo(3L);
        assertNull(job.getCreated());

        Path path = job.computeBatchJobFolderPath();
        assertNotNull(path, "folder path is always computed, never null");
        assertNotNull(job.getCreated(), "created must be defaulted as a side effect");
        assertEquals("late", path.getName(0).toString());
        assertEquals("execution", path.getName(1).toString());
        assertEquals("3", path.getFileName().toString());
    }

    // ------------------------------------------------------------------------------------------- //
    // computeDataFilePath: folder/[dataFileName], or null when no data file is defined
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void dataFilePathResolvesFileNameUnderFolderPath() {
        BatchData job = BatchTestFactory.batchData("imp", 9L, BatchTestFactory.dateOf(2022, java.util.Calendar.JUNE, 10));
        job.setDataFileName("data.json");

        Path expected = Paths.get("imp", "execution", "2022", "6", "9", "data.json");
        assertEquals(expected, job.computeDataFilePath());
    }

    @Test
    public void dataFilePathIsNullWhenNoDataFileName() {
        BatchData job = BatchTestFactory.batchData("imp", 9L, BatchTestFactory.dateOf(2022, java.util.Calendar.JUNE, 10));
        assertNull(job.computeDataFilePath(), "no data file name means no data file path");
    }

    // ------------------------------------------------------------------------------------------- //
    // onCreate (@PrePersist) created-date defaulting
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void onCreateDefaultsCreatedWhenNull() {
        BatchData job = new BatchData();
        assertNull(job.getCreated());

        job.onCreate();

        assertNotNull(job.getCreated(), "onCreate must default the created timestamp");
    }

    @Test
    public void onCreatePreservesExplicitlySetCreated() {
        Date explicit = BatchTestFactory.dateOf(2019, java.util.Calendar.MAY, 5);
        BatchData job = BatchTestFactory.batchData("j", 1L, explicit);

        job.onCreate();

        assertSame(explicit, job.getCreated(), "a pre-set created date must not be overwritten");
    }
}
