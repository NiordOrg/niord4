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

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

/**
 * Supplies the generic Niord framework defaults ({@code config/niord-core-defaults.yml},
 * shipped with niord-base) to any application that has the library on its classpath.
 * <p>
 * The defaults are added with {@code addLast}, i.e. at the <em>lowest</em> property-source
 * precedence: command-line args, environment variables, profiles and the deployable's own
 * Config Data ({@code application.yml}) all override them. That relies on this post-processor
 * running <em>after</em> Boot's {@code ConfigDataEnvironmentPostProcessor} has contributed the
 * deployable's config — which holds because unordered post-processors sort last. Do not add an
 * {@code @Order}/{@code Ordered} implementation here: the real-context precedence test
 * ({@code CoreDefaultsPrecedenceITTest}, a country deployable's test (SIFA-DK/niord-dk)) is the
 * regression guard for exactly that ordering.
 * <p>
 * Registered in {@code META-INF/spring.factories} under the Boot 4
 * {@code org.springframework.boot.EnvironmentPostProcessor} key.
 */
public final class NiordCoreDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var resource = new ClassPathResource("config/niord-core-defaults.yml");
        try {
            new YamlPropertySourceLoader()
                    .load("niord-core-defaults", resource)
                    .forEach(environment.getPropertySources()::addLast); // addLast = lowest precedence
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load Niord core defaults", e);
        }
    }
}
