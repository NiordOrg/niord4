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
import org.niord.web.AdminAppRedirectFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link AdminAppRedirectFilter}, which redirects a GET or HEAD for the backend's root
 * ({@code /}) to the administration web app named by the {@code adminAppUrl} setting, so that a
 * hash-routed message link built from {@code baseUri} opens the message.
 *
 * <p>Not profile-gated, like {@link TicketWebConfig}: such a link can reach the root of any deployment
 * whose {@code baseUri} is the backend's own URL, and the setting alone decides whether the root
 * redirects. Every deployable, and the integration-test application, component-scans {@code org.niord},
 * so this one configuration covers them all.
 *
 * <p>{@code HIGHEST_PRECEDENCE + 20} keeps the filter behind {@code RequestIdFilter}
 * ({@code HIGHEST_PRECEDENCE}, see {@link CorrelationConfig}), so a redirected request carries an id in
 * the log, and ahead of the Spring Security filter chain (registered at {@code -100}), which lets every
 * caller reach the root anyway.
 *
 * <p>Registered through {@link LazyFilter} rather than directly: filter registrations are applied while
 * the web server starts, before the ordinary singletons exist, so resolving the filter on its first
 * request is what lets it inject {@code NiordApp}, whose settings live in the database.
 */
@Configuration
public class AdminAppRedirectWebConfig {

    @Bean
    public FilterRegistrationBean<Filter> adminAppRedirectFilter(ApplicationContext ctx) {
        FilterRegistrationBean<Filter> reg =
                new FilterRegistrationBean<>(new LazyFilter(ctx, AdminAppRedirectFilter.class));
        reg.addUrlPatterns("/*");
        reg.setName(AdminAppRedirectFilter.class.getSimpleName());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return reg;
    }
}
