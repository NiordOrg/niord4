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
import org.niord.web.TicketServletFilter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TicketWebConfig} — the registration of {@link TicketServletFilter}.
 *
 * <p>The claim under test is the one that was previously false: the registration is profile-neutral.
 * The filter used to be declared in a configuration that was
 * {@code @Profile({"local","k8s"})}, so any build started without one of those profiles accepted
 * {@code ?ticket=} credentials and then ignored them. The runner below activates no profile at all,
 * so a profile gate reappearing on either the class or the bean method fails
 * {@link #registersTheTicketFilterWithNoProfileActive()} rather than shipping.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than a full {@code @SpringBootTest}: no database, no
 * containers, so it runs under Surefire — the same approach as {@code CorrelationWiringTest}.
 */
public class TicketWebConfigTest {

    /**
     * The order Spring Boot registers {@code springSecurityFilterChain} at
     * ({@code SecurityFilterProperties.DEFAULT_FILTER_ORDER}). Written out rather than imported: it is
     * an auto-configuration internal, and the point of the assertion is that the number is what this
     * registration was designed against.
     */
    private static final int SPRING_SECURITY_FILTER_ORDER = -100;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TicketWebConfig.class);

    @Test
    public void registersTheTicketFilterWithNoProfileActive() {
        runner.run(context -> assertTrue(context.containsBean("ticketFilter"),
                "the ticket filter is a credential filter and must be registered in every profile, "
                        + "including the integration-test context and a plain 'java -jar' start"));
    }

    /**
     * The two registration properties that carry a consequence, asserted together so the class stays a
     * guard rather than a restatement of the bean definition. The <em>order</em> has to leave the filter
     * ahead of the Spring Security chain, because the filter's {@code finally} — which clears the thread
     * local — must wrap the requests security answers with 401/403 without ever reaching a handler.
     * <em>Laziness</em> is what lets {@code TicketServletFilter} stay an ordinary {@code @Inject} class:
     * filter registrations are applied while the web server starts, before the ordinary singletons exist.
     * The end-to-end behaviour itself is covered by {@code TicketServletFilterITTest}.
     */
    @Test
    public void runsAheadOfTheSpringSecurityChainAndResolvesLazily() {
        runner.run(context -> {
            FilterRegistrationBean<?> reg = context.getBean("ticketFilter", FilterRegistrationBean.class);
            assertTrue(reg.getOrder() < SPRING_SECURITY_FILTER_ORDER,
                    "the ticket must be on the thread before the security chain runs, found order "
                            + reg.getOrder());
            assertInstanceOf(LazyFilter.class, reg.getFilter(),
                    "the injected TicketService must be resolved on first use, not at web-server startup");
            assertEquals(Set.of("/rest/*"), new LinkedHashSet<>(reg.getUrlPatterns()),
                    "tickets are only ever presented on REST download URLs");
        });
    }
}
