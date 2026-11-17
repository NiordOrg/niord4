/*
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
package org.niord.core.conf;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code @Scheduled} job runs on Boot's one {@code taskScheduler}, and it has four threads (SCHED3): the value
 * comes from niord-base's core defaults ({@code spring.task.scheduling.pool.size}), so a deployable that sets nothing
 * has it. With Boot's default of one thread, a long pass of one job held up every other job, the auto-publish and
 * auto-expiry of messages among them.
 */
class SchedulerPoolSizeITTest extends ITTest {

    @Autowired
    @Qualifier("taskScheduler")
    ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    DataSource dataSource;

    @Test
    void theSchedulerHasFourThreads() {
        assertEquals(4, taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize(),
                "the core defaults must give the scheduler of the @Scheduled jobs four threads");
    }

    /**
     * Four jobs at once can hold about nine database connections (SCHED4), so the core defaults let the pool grow to
     * twenty and keep ten when idle; with Boot's default of ten the REST requests waited for the one left.
     */
    @Test
    void theConnectionPoolGrowsToTwentyAndKeepsTen() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        assertEquals(20, pool.getMaximumPoolSize(),
                "the core defaults must let the connection pool grow to twenty");
        assertEquals(10, pool.getMinimumIdle(),
                "the core defaults must keep ten connections when idle, as Boot's default pool did");
    }

    /**
     * A task that holds its thread does not hold up the next one: four tasks that each wait for all four to have
     * started can only finish when they run at the same time. The application's own jobs run on the same threads,
     * so each task waits up to 30 seconds for a job that holds a thread to end.
     */
    @Test
    void fourTasksRunAtTheSameTime() throws Exception {
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch finished = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            taskScheduler.execute(() -> {
                started.countDown();
                try {
                    if (started.await(30, TimeUnit.SECONDS)) {
                        finished.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(finished.await(60, TimeUnit.SECONDS),
                "four tasks on the scheduler must run side by side, each on a thread of its own");
    }
}
