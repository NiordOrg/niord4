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

import org.jberet.spi.ArtifactFactory;
import org.springframework.context.ApplicationContext;

/**
 * JBeret {@link ArtifactFactory} backed by the Spring context — the replacement for the
 * CDI artifact resolution Quarkus/JBeret used. Batch job XML references artifacts by
 * {@code ref="name"}; the ported readers/processors/writers/batchlets are
 * {@code @Named("name") @Component} Spring beans, so they are resolved from the context
 * (with their {@code @Inject} dependencies satisfied). Anything not registered as a Spring
 * bean falls back to JBeret's default SE artifact factory.
 */
public class SpringArtifactFactory implements ArtifactFactory {

    private final ApplicationContext context;

    public SpringArtifactFactory(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public Object create(String ref, Class<?> type, ClassLoader classLoader) throws Exception {
        if (ref != null && context.containsBean(ref)) {
            return context.getBean(ref);
        }
        // Reflective fallback (avoids JBeret's default Weld/CDI artifact factory).
        Class<?> clazz = (type != null) ? type : Class.forName(ref, true, classLoader);
        return clazz.getDeclaredConstructor().newInstance();
    }

    @Override
    public Class<?> getArtifactClass(String ref, ClassLoader classLoader) {
        if (ref != null && context.containsBean(ref)) {
            return context.getType(ref);
        }
        try {
            return Class.forName(ref, true, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Override
    public void destroy(Object instance) {
        // Spring-managed singletons are disposed by the context; reflective fallbacks need nothing.
    }
}
