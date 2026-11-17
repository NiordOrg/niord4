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

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the country contract's start-up guard ({@link CountryContractGuard}) in every application that has niord-base
 * on its class path, at the earliest point each check can see what it checks:
 * <ul>
 *   <li>the properties and the class origins in {@link #initialize}, before any bean definition is read;</li>
 *   <li>the protected beans once every definition is registered and before any bean is created
 *       ({@code postProcessBeanFactory} of a registry post-processor);</li>
 *   <li>the handler methods, and the protected beans again with their instances' types, once the singletons exist
 *       and before the web server starts ({@link RuntimeCheck}, the bean
 *       {@value CountryContractGuard#RUNTIME_CHECK_BEAN}).</li>
 * </ul>
 *
 * <p>It is an initializer registered in niord-base's {@code META-INF/spring.factories}, not a component, so it applies
 * whatever packages a deployable scans: a country cannot leave it out by scanning less.
 */
public class CountryContractInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        List<String> findings = new ArrayList<>(CountryContractGuard.checkProperties(context.getEnvironment()));
        findings.addAll(CountryContractGuard.checkClassOrigins(context.getClassLoader()));
        CountryContractGuard.refuse(findings);
        context.addBeanFactoryPostProcessor(new DefinitionCheck());
    }

    /**
     * Checks the protected beans' definitions, and registers {@link RuntimeCheck}. Added to the context by the
     * initializer, so it runs its registry step before the configuration classes are read, and its bean-factory step
     * after every registry post-processor has registered its definitions.
     */
    static final class DefinitionCheck implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            RootBeanDefinition check = new RootBeanDefinition(RuntimeCheck.class);
            check.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            check.setLazyInit(false);
            registry.registerBeanDefinition(CountryContractGuard.RUNTIME_CHECK_BEAN, check);
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            CountryContractGuard.refuse(CountryContractGuard.checkBeanDefinitions(beanFactory));
        }
    }

    /**
     * Checks every handler method Spring MVC registered, and the protected beans again now that each singleton's type
     * is its instance's (a {@code @Bean} method declared to return {@code Object} hides its type until then). It runs
     * after the last singleton is created and before the web server starts, so a refused application never serves a
     * request.
     */
    static final class RuntimeCheck implements SmartInitializingSingleton, BeanFactoryAware {

        private ConfigurableListableBeanFactory beanFactory;

        @Override
        public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
            this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
        }

        @Override
        public void afterSingletonsInstantiated() {
            List<String> findings = new ArrayList<>(CountryContractGuard.checkBeanDefinitions(beanFactory));
            List<HandlerMethod> handlers = new ArrayList<>();
            for (RequestMappingHandlerMapping mapping
                    : beanFactory.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
                handlers.addAll(mapping.getHandlerMethods().values());
            }
            findings.addAll(CountryContractGuard.checkHandlers(handlers));
            CountryContractGuard.refuse(findings);
        }
    }
}
