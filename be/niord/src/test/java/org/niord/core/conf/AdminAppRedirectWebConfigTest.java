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

import org.junit.jupiter.api.Test;
import org.niord.web.AdminAppRedirectFilter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AdminAppRedirectWebConfig} — the registration of {@link AdminAppRedirectFilter}.
 *
 * <p>The registration is profile-neutral: whether the root redirects is decided by the {@code adminAppUrl}
 * setting, not by the profile a deployment happens to run with. The runners below activate no profile and
 * the Helm chart's {@code k8s} profile, so a profile gate appearing on the class or the bean method fails
 * here rather than shipping.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than a full {@code @SpringBootTest}: no database, no
 * containers, so it runs under Surefire — the same approach as {@link TicketWebConfigTest}.
 */
public class AdminAppRedirectWebConfigTest {

    /**
     * The order Spring Boot registers {@code springSecurityFilterChain} at
     * ({@code SecurityFilterProperties.DEFAULT_FILTER_ORDER}). Written out rather than imported, as in
     * {@link TicketWebConfigTest}.
     */
    private static final int SPRING_SECURITY_FILTER_ORDER = -100;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdminAppRedirectWebConfig.class);

    @Test
    public void registersTheRedirectFilterWithNoProfileActive() {
        runner.run(context -> assertTrue(context.containsBean("adminAppRedirectFilter"),
                "a deployment started without a profile must still redirect its root to the admin app"));
    }

    @Test
    public void registersTheRedirectFilterUnderTheK8sProfile() {
        runner.withPropertyValues("spring.profiles.active=k8s")
                .run(context -> assertTrue(context.containsBean("adminAppRedirectFilter"),
                        "the Helm chart runs the k8s profile, and its root must redirect to the admin app too"));
    }

    /**
     * The registration properties that carry a consequence. The filter must see the root, so it covers
     * every request and picks the root out itself; it runs behind {@code RequestIdFilter}, so a redirected
     * request carries an id, and ahead of the Spring Security chain. <em>Laziness</em> is what lets
     * {@code AdminAppRedirectFilter} inject {@code NiordApp}: filter registrations are applied while the web
     * server starts, before the ordinary singletons exist.
     */
    @Test
    public void coversEveryRequestBetweenTheRequestIdFilterAndSpringSecurityAndResolvesLazily() {
        int requestIdOrder = new CorrelationConfig().requestIdFilter().getOrder();
        runner.run(context -> {
            FilterRegistrationBean<?> reg =
                    context.getBean("adminAppRedirectFilter", FilterRegistrationBean.class);
            assertTrue(reg.getOrder() > requestIdOrder,
                    "the request id must be on the thread first, found order " + reg.getOrder());
            assertTrue(reg.getOrder() < SPRING_SECURITY_FILTER_ORDER,
                    "the filter must run ahead of the security chain, found order " + reg.getOrder());
            assertInstanceOf(LazyFilter.class, reg.getFilter(),
                    "the injected NiordApp must be resolved on first use, not at web-server startup");
            assertEquals(Set.of("/*"), new LinkedHashSet<>(reg.getUrlPatterns()),
                    "the filter must see the root request");
        });
    }
}
