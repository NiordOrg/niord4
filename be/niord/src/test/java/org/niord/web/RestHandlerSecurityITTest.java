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
package org.niord.web;

import org.junit.jupiter.api.Test;
import org.niord.core.conf.CountryContractGuard;
import org.niord.test.CountryBuildGroup;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime counterpart of {@link RestControllerSecurityAnnotationTest}: walks the handler methods the
 * running application actually registered with Spring MVC and asserts that every one of them resolves a
 * JSR-250 annotation, the way Spring Security resolves it.
 *
 * <p>The unit guard finds controllers by scanning the classpath for the {@code @Controller} stereotype. This
 * test asks {@link RequestMappingHandlerMapping} instead, so it also sees a handler registered in a way the
 * scan does not anticipate — a controller bean declared by a {@code @Bean} method, a handler contributed by a
 * library — and it sees exactly the methods, inherited and non-public ones included, that are mapped. Each
 * is resolved with the same scanner and rules as the unit guard ({@link
 * RestControllerSecurityAnnotationTest#securityProblems}), with the handler's bean type as the target class.
 *
 * <p>Handlers of classes outside {@code org.niord} are framework code; they carry no JSR-250 annotation and
 * are listed in {@link CountryContractGuard#REVIEWED_FRAMEWORK_HANDLERS} with what they serve, so that a new one
 * is a deliberate addition rather than an unnoticed public endpoint. The start-up guard exempts the same list.
 *
 * <p>The test also asserts that the start-up guard's runtime check is in the context: it is what refuses an
 * unannotated handler of a country's own, outside {@code org.niord} too, in the real application. The class is in
 * the country build group, so it runs in every country build.
 *
 * <p>The unit guard remains the stronger one for conditional controllers: this context runs with the test
 * configuration, so a controller behind an unmet {@code @ConditionalOnProperty} (the SECOM administration
 * API) is not registered here, while the condition-blind scan still checks it.
 */
@CountryBuildGroup
class RestHandlerSecurityITTest extends ITTest {

    /** Framework (non-{@code org.niord}) handler classes the application registers, reviewed */
    private static final Set<String> REVIEWED_FRAMEWORK_HANDLERS = CountryContractGuard.REVIEWED_FRAMEWORK_HANDLERS;

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void everyRegisteredHandlerResolvesAnExplicitSecurityAnnotation() {
        Map<Class<?>, Set<Method>> handlersByType = new HashMap<>();
        Set<String> frameworkHandlerTypes = new TreeSet<>();

        for (RequestMappingHandlerMapping mapping
                : applicationContext.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            for (HandlerMethod handler : mapping.getHandlerMethods().values()) {
                Class<?> beanType = handler.getBeanType();
                if (beanType.getName().startsWith("org.niord.")) {
                    handlersByType.computeIfAbsent(beanType, t -> new HashSet<>()).add(handler.getMethod());
                } else {
                    frameworkHandlerTypes.add(beanType.getName());
                }
            }
        }

        List<String> problems = new ArrayList<>();
        int handlerMethods = 0;
        for (Map.Entry<Class<?>, Set<Method>> entry : handlersByType.entrySet()) {
            handlerMethods += entry.getValue().size();
            problems.addAll(RestControllerSecurityAnnotationTest.securityProblems(entry.getKey(), entry.getValue()));
        }
        problems.sort(null);

        // Guard the guard: a context that registered no REST layer must not pass vacuously
        assertTrue(handlersByType.containsKey(MessageRestService.class),
                "sanity: the running application must have registered the REST layer");
        assertTrue(handlerMethods > 200,
                "sanity: expected the full REST layer, found only " + handlerMethods + " handler methods");

        assertEquals(List.of(), problems,
                "Every handler method registered with Spring MVC must resolve @PermitAll, @RolesAllowed with "
                        + "a known role, or @DenyAll, the way Spring Security resolves it; an unannotated "
                        + "handler is silently PUBLIC");
        assertEquals(new TreeSet<>(REVIEWED_FRAMEWORK_HANDLERS), frameworkHandlerTypes,
                "A framework handler carries no JSR-250 annotation and is public. Review what it serves and "
                        + "add it to CountryContractGuard.REVIEWED_FRAMEWORK_HANDLERS with a comment, or remove the "
                        + "entry if it is gone");
    }

    @Test
    void theStartUpGuardChecksTheRunningApplication() {
        assertTrue(applicationContext.containsBean(CountryContractGuard.RUNTIME_CHECK_BEAN),
                "the country contract's runtime check (CountryContractInitializer, registered in niord-base's "
                        + "META-INF/spring.factories) must be in every application context");
    }
}
