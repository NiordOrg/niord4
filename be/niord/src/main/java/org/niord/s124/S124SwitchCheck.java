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
package org.niord.s124;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;

/**
 * The two subsystem switches and the rule between them (the S-124 opt-out, the owner 2026-11-01).
 * {@code niord.s124.enabled} is the parent: on by default, and off it removes the S-124 generator
 * ({@link S124Service}) and the public {@code /rest/S-124} API ({@link S124RestService}), so the
 * producer code is neither read nor validated. {@code niord.secom.enabled} is the child: off by
 * default, and on it needs S-124, since SECOM exchanges S-124 datasets.
 * <p>
 * Start-up refuses S-124 off with SECOM on, with a message naming both keys. The check is a
 * {@link BeanFactoryPostProcessor}, which runs before any singleton is created, so the refusal
 * comes before {@code S124ExchangeSetService}'s unsatisfied dependency on the absent generator.
 * <p>
 * {@link #s124Enabled} and {@link #secomEnabled} apply exactly the {@code @ConditionalOnProperty}
 * rules of the beans they stand for (a value counts as on only when it is {@code true}, in any
 * case), so {@code /rest/settings/web} never disagrees with which beans exist. The automatic
 * cancellation warning is not gated by either switch (decision 2 of
 * {@code be/docs/s124-cancellation-plan.md}).
 * <p>
 * The class is registered only by the {@code org.niord} component scan of the applications
 * ({@code NiordApplication}, {@code NiordTestApplication}); {@code S124SwitchCheckTest} asserts
 * that the scan finds it, since without it the refusal would fall back to the unsatisfied
 * dependency.
 */
@Component
public final class S124SwitchCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    /** The boot property that switches S-124 on (default) or off */
    public static final String ENABLED_PROPERTY = "niord.s124.enabled";

    /** The boot property that switches SECOM on; off by default, and on it needs S-124 */
    public static final String SECOM_ENABLED_PROPERTY = "niord.secom.enabled";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * Whether S-124 runs: the key is missing, or {@code true} in any case
     * ({@code havingValue = "true", matchIfMissing = true})
     */
    public static boolean s124Enabled(PropertyResolver properties) {
        String value = properties.getProperty(ENABLED_PROPERTY);
        return value == null || "true".equalsIgnoreCase(value);
    }

    /**
     * Whether SECOM runs: the key is {@code true} in any case ({@code havingValue = "true"}),
     * and S-124 runs too (start-up refuses the other combination)
     */
    public static boolean secomEnabled(PropertyResolver properties) {
        return "true".equalsIgnoreCase(properties.getProperty(SECOM_ENABLED_PROPERTY)) && s124Enabled(properties);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!s124Enabled(environment) && "true".equalsIgnoreCase(environment.getProperty(SECOM_ENABLED_PROPERTY))) {
            throw new IllegalStateException(SECOM_ENABLED_PROPERTY + "=true needs " + ENABLED_PROPERTY
                    + ": SECOM exchanges S-124 datasets, and " + ENABLED_PROPERTY + " is false. Set "
                    + ENABLED_PROPERTY + " to true, or " + SECOM_ENABLED_PROPERTY + " to false.");
        }
    }
}
