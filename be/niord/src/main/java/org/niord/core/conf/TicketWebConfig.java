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

import jakarta.servlet.Filter;
import org.niord.web.TicketServletFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link TicketServletFilter}, which turns a {@code ?ticket=} query parameter into the
 * per-request ticket identity — domain, user and roles — that {@code UserService.currentUser()},
 * {@code UserService.isCallerInRole(String)} and {@code DomainService.currentDomain()} fall back on
 * for an otherwise anonymous caller.
 *
 * <p>Deliberately <em>not</em> profile-gated — a ticket is a credential, and every deployment that
 * serves {@code /rest/*} consumes tickets: the frontend's admin exports, and
 * {@code PublicationRestService}'s HTTP self-call to {@code /rest/message-reports/report.pdf}, whose
 * <em>only</em> credential is the ticket in its query string. Behind a profile gate, a build started
 * without the {@code local}/{@code k8s} profile — the runtime image's bare {@code java -jar} entry point
 * outside the Helm chart, or the integration-test context — would silently ignore every one of them.
 * Role-scoped exports then fail closed with a 401, but the {@code @PermitAll} report self-call quietly
 * returns a document searched with no current domain: no domain fallback for the search, and no domain
 * sort order. All three deployables
 * ({@code niord-app}, each country deployable and the test twin) component-scan {@code org.niord}, so this
 * one configuration covers them all.
 *
 * <p>Order and laziness are carried over unchanged from the previous registration.
 * {@code HIGHEST_PRECEDENCE + 30} keeps the filter behind {@code RequestIdFilter}
 * ({@code HIGHEST_PRECEDENCE}, see {@link CorrelationConfig}), and ahead of the Spring Security
 * filter chain (registered at {@code -100}). Ahead of security is what matters: the ticket identity is
 * then already on the thread for every reader downstream of the handler mapping, and the filter's
 * {@code finally} — which clears the thread local — wraps the whole security chain, including the
 * requests it answers with 401 or 403 without ever reaching a handler.
 *
 * <p>Registered through {@link LazyFilter} rather than directly: filter registrations are applied
 * while the web server starts, before the ordinary singletons exist, so resolving the filter on its
 * first request is what lets it stay an ordinary {@code @Inject} class instead of a Spring bean.
 */
@Configuration
public class TicketWebConfig {

    @Bean
    public FilterRegistrationBean<Filter> ticketFilter(ApplicationContext ctx) {
        FilterRegistrationBean<Filter> reg =
                new FilterRegistrationBean<>(new LazyFilter(ctx, TicketServletFilter.class));
        reg.addUrlPatterns("/rest/*");
        reg.setName(TicketServletFilter.class.getSimpleName());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return reg;
    }
}
