/*
 * Copyright 2024 Danish Maritime Authority.
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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.niord.web.wms.WmsProxyServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Registers the {@link WmsProxyServlet} (mapped to {@code /wms/*} by its {@code @WebServlet}
 * under Quarkus) with the embedded servlet container — Spring does not scan {@code @WebServlet}.
 *
 * <p>Servlet registrations are processed while the web server is created, which is <em>before</em>
 * the JPA {@code EntityManager} is initialised. {@code WmsProxyServlet} has {@code @Setting} fields
 * read from the database, so it cannot be instantiated that early. We therefore register a thin
 * {@link DelegatingWmsServlet} (no Spring/JPA dependencies) that resolves the real, fully-wired
 * {@code WmsProxyServlet} Spring bean on its first request — by which point persistence is up.
 */
@Configuration
public class WmsServletConfig {

    @Bean
    public ServletRegistrationBean<DelegatingWmsServlet> wmsProxyServletRegistration(ApplicationContext context) {
        ServletRegistrationBean<DelegatingWmsServlet> registration =
                new ServletRegistrationBean<>(new DelegatingWmsServlet(context), "/wms/*");
        registration.setName("wmsProxyServlet");
        return registration;
    }

    /** Resolves and delegates to the {@link WmsProxyServlet} Spring bean lazily, on first use. */
    static class DelegatingWmsServlet extends HttpServlet {
        private final ApplicationContext context;
        private volatile WmsProxyServlet delegate;

        DelegatingWmsServlet(ApplicationContext context) {
            this.context = context;
        }

        private WmsProxyServlet delegate() {
            WmsProxyServlet d = delegate;
            if (d == null) {
                synchronized (this) {
                    d = delegate;
                    if (d == null) {
                        // Create the servlet now (JPA is up by first request) and let the bean factory
                        // run @Autowired/@Inject AND the @Setting BeanPostProcessor on it — without it
                        // being a registered Servlet bean (which Spring Boot would auto-map before JPA).
                        d = new WmsProxyServlet();
                        context.getAutowireCapableBeanFactory().autowireBean(d);
                        delegate = d;
                    }
                }
            }
            return d;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            delegate().service(req, resp);
        }
    }
}
