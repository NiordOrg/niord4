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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.context.ApplicationContext;

import java.io.IOException;

/**
 * Wraps a {@code @WebFilter} class and resolves it lazily on the first request — instantiating it and
 * running Spring autowiring (so its {@code @Inject}/{@code @Setting} fields are populated) only after
 * the persistence layer is up. Filter registrations are applied while the web server starts, which is
 * before JPA is ready, so the real filter cannot be created eagerly.
 */
public class LazyFilter implements Filter {

    private final ApplicationContext context;
    private final Class<? extends Filter> filterClass;
    private volatile Filter delegate;

    public LazyFilter(ApplicationContext context, Class<? extends Filter> filterClass) {
        this.context = context;
        this.filterClass = filterClass;
    }

    private Filter delegate() {
        Filter d = delegate;
        if (d == null) {
            synchronized (this) {
                d = delegate;
                if (d == null) {
                    try {
                        d = filterClass.getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot instantiate filter " + filterClass, e);
                    }
                    context.getAutowireCapableBeanFactory().autowireBean(d);
                    delegate = d;
                }
            }
        }
        return d;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        delegate().doFilter(request, response, chain);
    }
}
