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
package org.niord.core.conf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.util.MdcContext;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that Spring Boot actually <em>applies</em> {@link CorrelationConfig#mdcTaskDecorator()}
 * to the executors it auto-configures. Every other test of the decorator drives it by hand and would
 * still pass if the bean were never wired to anything — this is the one that would fail.
 *
 * <p>It also pins the constraint the decorator's design rests on: Boot feeds the <em>same</em>
 * {@code ObjectProvider<TaskDecorator>} to the {@code @Async} executor and to the {@code @Scheduled}
 * scheduler, so a single bean has to serve both. If a future Boot upgrade stopped applying it to
 * one of them, or if someone added a second {@code TaskDecorator} bean expecting the two to be kept
 * apart, these tests are what notices.
 *
 * <p>Uses {@link ApplicationContextRunner} against the real auto-configuration rather than a full
 * {@code @SpringBootTest}: no database, no containers, so it runs under Surefire.
 */
public class CorrelationWiringTest {

    @Configuration
    @EnableScheduling
    static class SchedulingEnabled {
        // TaskSchedulingAutoConfiguration only contributes a `taskScheduler` when scheduling is on.
    }

    @AfterEach
    public void clearMdc() {
        MDC.clear();
    }

    /**
     * The end-to-end {@code @Async} claim: work submitted from a thread carrying a request id sees
     * that id on the worker. This is the wiring that makes "the log line from the async continuation
     * names the request that started it" true.
     */
    @Test
    public void bootAppliesTheDecoratorToTheAsyncExecutor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(CorrelationConfig.class)
                .run(context -> {
                    AsyncTaskExecutor executor =
                            context.getBean("applicationTaskExecutor", AsyncTaskExecutor.class);

                    MDC.put(MdcContext.REQUEST_ID, "req-wired");
                    MDC.put(MdcContext.DOMAIN, "niord-client-nm");

                    String seenId = executor.submit(() -> MDC.get(MdcContext.REQUEST_ID))
                            .get(5, TimeUnit.SECONDS);
                    String seenDomain = executor.submit(() -> MDC.get(MdcContext.DOMAIN))
                            .get(5, TimeUnit.SECONDS);

                    assertEquals("req-wired", seenId,
                            "Boot must apply the TaskDecorator bean to applicationTaskExecutor");
                    assertEquals("niord-client-nm", seenDomain);
                });
    }

    /**
     * The same single bean must also reach the scheduler — and there produce a <em>fresh</em> id per
     * execution rather than inheriting anything, because a scheduled sweep has no originating
     * request.
     */
    @Test
    public void bootAppliesTheDecoratorToTheScheduler() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(CorrelationConfig.class, SchedulingEnabled.class)
                .run(context -> {
                    AsyncTaskExecutor scheduler = context.getBean("taskScheduler", AsyncTaskExecutor.class);

                    MDC.clear();  // a scheduled sweep is submitted with no correlation context
                    List<String> ids = Collections.synchronizedList(new ArrayList<>());
                    CountDownLatch done = new CountDownLatch(2);
                    Runnable task = () -> {
                        ids.add(MDC.get(MdcContext.REQUEST_ID));
                        done.countDown();
                    };

                    scheduler.execute(task);
                    scheduler.execute(task);

                    assertTrue(done.await(5, TimeUnit.SECONDS), "scheduled tasks did not run");
                    assertEquals(2, ids.size());
                    ids.forEach(id -> assertTrue(id != null && id.startsWith("job-"),
                            "Boot must apply the TaskDecorator bean to taskScheduler; saw: " + id));
                    assertNotEquals(ids.get(0), ids.get(1),
                            "each scheduled execution needs its own id");
                });
    }
}
