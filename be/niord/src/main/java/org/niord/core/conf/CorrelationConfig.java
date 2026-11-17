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

import org.niord.core.util.MdcContext;
import org.niord.core.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Wires up log correlation: the per-request id filter, and the MDC hand-off onto Spring's managed
 * worker threads.
 *
 * <p>Deliberately <em>not</em> profile-gated — correlation ids must exist in every profile,
 * including the integration tests. All three deployables
 * ({@code niord-app}, each country deployable, and the test twin) component-scan {@code org.niord}, so this
 * one configuration covers them all.
 */
@Configuration
public class CorrelationConfig {

    /**
     * Registers {@link RequestIdFilter} at {@link Ordered#HIGHEST_PRECEDENCE} — ahead of every other
     * filter and of the Spring Security filter chain (registered at {@code -100}), so a request
     * rejected with 401 or 403 still gets an id in its logs and its response.
     *
     * <p>Registered directly rather than through {@code LazyFilter}: that indirection exists for
     * filters that inject services created after JPA, and this filter has no dependencies.
     */
    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /**
     * The single {@link TaskDecorator} bean, applied to both {@code @Async} work and
     * {@code @Scheduled} work.
     *
     * <p>"Single" is a constraint, not a preference. Spring Boot passes the <em>same</em>
     * {@code ObjectProvider<TaskDecorator>} to {@code ThreadPoolTaskExecutorBuilder} (the
     * {@code @Async} executor) and to {@code ThreadPoolTaskSchedulerBuilder} (the {@code @Scheduled}
     * scheduler), and composes multiple beans into a {@code CompositeTaskDecorator}. So one
     * decorator serves both executors, and it has to tell them apart at runtime rather than at
     * bean-definition time — which it does by asking whether the submitting thread had a
     * correlation id:
     * <ul>
     *   <li><b>it did</b> — this is {@code @Async} work submitted from a request (or from a job):
     *       inherit that whole context, so the async continuation joins the same story.</li>
     *   <li><b>it did not</b> — this is a {@code @Scheduled} sweep: mint a fresh {@code job-} id per
     *       execution. See {@link MdcContext#withFreshJobId} for why the id has to be created inside
     *       the returned {@code Runnable}.</li>
     * </ul>
     *
     * <p>Note this covers only Spring-managed executors — the {@code applicationTaskExecutor} and the
     * {@code taskScheduler}, and nothing else. Work handed to a raw {@code java.util.concurrent} pool
     * or scheduled on a {@code java.util.Timer} never passes through this decorator; those sites are
     * wrapped individually with {@link MdcContext}, or excluded with a reason recorded in the code.
     * {@link MdcContext} carries the full inventory.
     */
    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            // Evaluated on the SUBMITTING thread, at decoration time.
            Map<String, String> submitted = MDC.getCopyOfContextMap();
            return submitted != null && submitted.containsKey(MdcContext.REQUEST_ID)
                    ? MdcContext.withContext(submitted, runnable)
                    : MdcContext.withFreshJobId(runnable);
        };
    }
}
