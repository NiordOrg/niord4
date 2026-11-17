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
package org.niord.web;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.Test;
import org.niord.core.conf.CountryContractGuard;
import org.niord.core.user.Roles;
import org.niord.test.CountryBuildGroup;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.security.core.annotation.SecurityAnnotationScanner;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the REST layer's authorization model against its one structural trap.
 *
 * <p>Authorization is enforced solely by JSR-250 method security
 * ({@code @EnableMethodSecurity(jsr250Enabled = true)} in the core {@code SecurityConfig}); the HTTP
 * layer is {@code permitAll}. Under that model an endpoint whose handler method resolves <em>no</em>
 * {@code @PermitAll}/{@code @RolesAllowed}/{@code @DenyAll} annotation is silently public — a forgotten
 * annotation fails OPEN. This test turns that trap into a build failure: every handler method of every
 * controller must resolve an explicit security annotation. Intentionally public endpoints say so with
 * {@code @PermitAll}; nothing is public merely by omission.
 *
 * <p>The test enumerates handlers and resolves their annotations the way the runtime does, so that what
 * it passes is what is enforced:
 * <ul>
 *   <li><b>Which classes.</b> Every {@code @Controller}, which includes every {@code @RestController}
 *       (it is meta-annotated with {@code @Controller}): Spring MVC maps the handlers of a
 *       {@code @Controller} + {@code @ResponseBody} class exactly like those of a
 *       {@code @RestController}.</li>
 *   <li><b>Which methods.</b> Every method {@code RequestMappingHandlerMapping} would map, found with the
 *       same {@link MethodIntrospector} walk: methods inherited from a superclass or an interface, and
 *       non-public (protected, package-private) methods, which Spring MVC maps as well —
 *       {@code Class.getMethods()} would see only the public ones.</li>
 *   <li><b>Which annotation.</b> Resolved with Spring Security's own {@link SecurityAnnotationScanner}
 *       for JSR-250 — the scanner {@code Jsr250AuthorizationManager} uses — with the controller as the
 *       target class. That settles precedence exactly as enforcement does: an annotation on the method
 *       (or on a method it overrides) wins over one on a class, and the class-level annotation that
 *       applies is the one on the class <em>declaring</em> the method, not on the controller that
 *       inherits it. Competing annotations make the scanner throw, as they would at runtime.</li>
 *   <li><b>Which roles.</b> Every {@code @RolesAllowed} value must be one of the roles in {@link Roles};
 *       a misspelt role locks every caller out and hides that the endpoint was meant to be open to
 *       someone.</li>
 * </ul>
 *
 * <p>The classpath scan is deliberately <em>condition-blind</em>. A plain
 * {@code ClassPathScanningCandidateComponentProvider} evaluates {@code @Conditional}/{@code @Profile}
 * against the test environment and silently drops every candidate whose condition does not match —
 * so a controller that only exists in some deployments, i.e. the kind that gets reviewed least,
 * would be waved through. {@code SecomAdminRestService} ({@code @ConditionalOnProperty}
 * {@code niord.secom.enabled}) sat outside this guard for exactly that reason. Overriding
 * {@code isCandidateComponent(MetadataReader)} keeps the include filter and drops only the condition
 * evaluation. The runtime counterpart, {@code RestHandlerSecurityITTest}, walks the handlers the running
 * application actually registered.
 */
@CountryBuildGroup
public class RestControllerSecurityAnnotationTest {

    /**
     * The JSR-250 scanner Spring Security's {@code Jsr250AuthorizationManager} resolves annotations with: the start-up
     * guard's, so that this test and the guard resolve annotations alike.
     */
    static final SecurityAnnotationScanner<Annotation> JSR250 = CountryContractGuard.JSR250;

    /** Every role an {@code @RolesAllowed} may name. */
    static final Set<String> KNOWN_ROLES =
            Set.of(Roles.USER, Roles.EDITOR, Roles.ADMIN, Roles.SYSADMIN, Roles.PUBLICATION_CURATE);

    @Test
    public void everyRestHandlerMethodResolvesAnExplicitSecurityAnnotation() throws Exception {

        TypeFilter controllers = new AnnotationTypeFilter(Controller.class);
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    /** The include filter alone, without the condition evaluation super applies */
                    @Override
                    protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
                        return controllers.match(metadataReader, getMetadataReaderFactory());
                    }
                };
        scanner.addIncludeFilter(controllers);

        Set<String> controllerNames = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        int handlerMethods = 0;

        for (BeanDefinition candidate : scanner.findCandidateComponents("org.niord")) {
            Class<?> controller = Class.forName(
                    candidate.getBeanClassName(), false, getClass().getClassLoader());
            controllerNames.add(controller.getName());

            Set<Method> handlers = handlerMethods(controller);
            handlerMethods += handlers.size();
            problems.addAll(securityProblems(controller, handlers));
        }

        // Guard the guard: an empty or partial classpath scan must not pass vacuously
        assertTrue(controllerNames.contains("org.niord.web.MessageRestService"),
                "sanity: the classpath scan must find the REST layer, found: " + controllerNames);
        assertTrue(handlerMethods > 200,
                "sanity: expected the full REST layer, found only " + handlerMethods + " handler methods");

        assertEquals(List.of(), problems,
                "Every REST handler method must resolve @PermitAll (intentionally public), @RolesAllowed "
                        + "with a known role, or @DenyAll, the way Spring Security resolves it: on the method, "
                        + "or on the class DECLARING the method. An unannotated handler is silently PUBLIC "
                        + "under permitAll-at-HTTP-layer + JSR-250 method security");
    }

    /**
     * The methods Spring MVC maps as handlers of the given controller: every method, public or not,
     * declared on the class or inherited from a superclass or an interface, that carries (or overrides
     * a method carrying) a {@code @RequestMapping}, {@code @GetMapping} and so on.
     */
    static Set<Method> handlerMethods(Class<?> controller) {
        return MethodIntrospector.selectMethods(controller,
                (MethodIntrospector.MetadataLookup<Boolean>) method ->
                        AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class) ? Boolean.TRUE : null)
                .keySet();
    }

    /** What is wrong with the security of the given handler methods of the given controller, if anything. */
    static List<String> securityProblems(Class<?> controller, Set<Method> handlers) {
        List<String> problems = new ArrayList<>();
        for (Method method : handlers) {
            String name = controller.getSimpleName() + "." + method.getName();
            Annotation annotation;
            try {
                annotation = JSR250.scan(method, controller);
            } catch (RuntimeException e) {
                problems.add(name + ": " + e.getMessage());
                continue;
            }
            if (annotation == null) {
                problems.add(name + ": no security annotation");
            } else if (annotation instanceof RolesAllowed rolesAllowed) {
                for (String role : rolesAllowed.value()) {
                    if (!KNOWN_ROLES.contains(role)) {
                        problems.add(name + ": unknown role '" + role + "'");
                    }
                }
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------------------------------- //
    // The guard's own negative tests
    //
    // The fixtures below are plain classes, deliberately NOT @Controller: the integration tests'
    // application scans all of org.niord, test classes included, and would register a fixture
    // stereotype as a live controller. The handler walk and the resolution do not need the stereotype.
    // ------------------------------------------------------------------------------------------- //

    @RolesAllowed(Roles.ADMIN)
    static class NonPublicHandlers {
        @GetMapping("/secured")
        public void secured() { }

        @GetMapping("/protected")
        @PermitAll
        protected void protectedButAnnotated() { }

        @GetMapping("/package-private")
        void packagePrivateInheritsTheClassAnnotation() { }
    }

    static class UnannotatedBase {
        @GetMapping("/inherited")
        public void inheritedWithNoAnnotation() { }

        @GetMapping("/inherited-protected")
        protected void inheritedProtectedWithNoAnnotation() { }
    }

    /**
     * The class-level annotation does NOT cover the inherited handlers: Spring Security takes the
     * class-level annotation from the class declaring the method, and {@link UnannotatedBase} has none.
     */
    @RolesAllowed(Roles.SYSADMIN)
    static class InheritsUnsecuredHandlers extends UnannotatedBase {
        @GetMapping("/own")
        public void own() { }
    }

    static class UnannotatedProtectedHandler {
        @GetMapping("/open")
        protected void open() { }
    }

    @PermitAll
    static class MethodOverridesClass {
        @GetMapping("/restricted")
        @RolesAllowed("admn")
        public void misspeltRole() { }

        @GetMapping("/both")
        @RolesAllowed(Roles.ADMIN)
        @PermitAll
        public void competingAnnotations() { }
    }

    private static List<String> problemsOf(Class<?> fixture) {
        return securityProblems(fixture, handlerMethods(fixture));
    }

    @Test
    public void nonPublicHandlersAreEnumerated() {
        Set<String> names = new TreeSet<>();
        handlerMethods(NonPublicHandlers.class).forEach(m -> names.add(m.getName()));

        assertEquals(Set.of("secured", "protectedButAnnotated", "packagePrivateInheritsTheClassAnnotation"), names);
        assertEquals(List.of(), problemsOf(NonPublicHandlers.class));
    }

    @Test
    public void anUnannotatedNonPublicHandlerIsReported() {
        assertEquals(List.of("UnannotatedProtectedHandler.open: no security annotation"),
                problemsOf(UnannotatedProtectedHandler.class));
    }

    @Test
    public void anInheritedHandlerIsCheckedAgainstTheClassDeclaringIt() {
        List<String> problems = new ArrayList<>(problemsOf(InheritsUnsecuredHandlers.class));
        problems.sort(null);

        assertEquals(List.of(
                "InheritsUnsecuredHandlers.inheritedProtectedWithNoAnnotation: no security annotation",
                "InheritsUnsecuredHandlers.inheritedWithNoAnnotation: no security annotation"), problems);
    }

    @Test
    public void aMethodAnnotationWinsOverTheClassAndItsRolesAreChecked() {
        List<String> problems = problemsOf(MethodOverridesClass.class);

        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.contains("MethodOverridesClass.misspeltRole: unknown role 'admn'"), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("MethodOverridesClass.competingAnnotations: ")),
                "competing annotations fail resolution, as they would at runtime: " + problems);
    }
}
