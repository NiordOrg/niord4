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

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.niord.core.domain.DomainService;
import org.niord.core.mailinglist.MailingListExecutionService;
import org.niord.core.message.MessageMailService;
import org.niord.core.script.FmTemplateService;
import org.niord.core.script.JsResourceService;
import org.niord.core.script.ScriptResourceService;
import org.niord.core.settings.SettingsService;
import org.niord.core.user.TicketService;
import org.niord.core.user.UserService;
import org.niord.core.web.NiordDomainFilter;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.OriginTrackedResource;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.core.annotation.SecurityAnnotationScanner;
import org.springframework.security.core.annotation.SecurityAnnotationScanners;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The start-up guard of the country contract (docs/fe-country-split-plan.md §4.11 rule 3, be/docs/country-api.md).
 *
 * <p>A country deployable builds on niord-base and may add beans, controllers and configuration of its own. What it
 * may not do is replace or duplicate what holds the security model together: the beans, properties and classes listed
 * here. Under that model the HTTP layer is {@code permitAll} and JSR-250 method security is the whole guard (see
 * {@link SecurityConfig}), so a controller without an annotation, a second {@code SecurityFilterChain} or a
 * {@code JwtDecoder} of the country's own would open the application without anything failing. The guard turns each of
 * these into a refused start that names what it found.
 *
 * <p>Each check is a static method that only reads and returns its findings, one line each, so that the tests can call
 * it on a hand-made bean factory or environment. {@link CountryContractInitializer} runs them at start-up, in every
 * application that has niord-base on its class path, and {@link #refuse} turns the findings into one exception.
 *
 * <p>"From niord-base" means loaded from the same code source as this class: a country's own classes, also those in
 * {@code org.niord.*} packages, come from another jar, so a package prefix cannot tell them apart.
 *
 * <p>The guard is against carelessness, as the rest of the contract is: a country that wants to defeat it can. Its
 * tests and the security tests run in every country build (the country build group, {@code @CountryBuildGroup}).
 */
public final class CountryContractGuard {

    /** The name of the bean {@link CountryContractInitializer} registers to check the handlers and beans at start-up */
    public static final String RUNTIME_CHECK_BEAN = "niordCountryContractCheck";

    /**
     * The JSR-250 scanner Spring Security's {@code Jsr250AuthorizationManager} resolves annotations with, so that what
     * the guard and the tests accept is what is enforced. Competing annotations make it throw, as they do at runtime.
     */
    public static final SecurityAnnotationScanner<Annotation> JSR250 =
            SecurityAnnotationScanners.requireUnique(List.of(DenyAll.class, PermitAll.class, RolesAllowed.class));

    /**
     * Framework handler classes that carry no JSR-250 annotation and are public by review, with what they serve.
     * A new one is a deliberate addition here, never an unnoticed public endpoint.
     */
    public static final Set<String> REVIEWED_FRAMEWORK_HANDLERS = Set.of(
            // Spring Boot's /error page: renders the error attributes of the failed request it is forwarded
            // from (as RFC 9457 problem details or the default error body), no data of its own.
            "org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController");

    /**
     * The beans only niord-base declares. A country may neither replace one (a subclass, a {@code @Primary} bean, a
     * factory method of its own) nor add a second: each holds part of the authentication, the authorisation, the
     * request's domain, or the templates, scripts and mails that run with full privileges. A country that needs other
     * behaviour asks for an extension point.
     */
    public static final List<Class<?>> PROTECTED_BEAN_TYPES = List.of(
            SecurityFilterChain.class,
            JwtAuthenticationConverter.class,
            GrantedAuthorityDefaults.class,
            NiordDomainFilter.class,
            DomainService.class,
            UserService.class,
            TicketService.class,
            SettingsService.class,
            ScriptResourceService.class,
            FmTemplateService.class,
            JsResourceService.class,
            MessageMailService.class,
            MailingListExecutionService.class,
            WebAppConfig.class);

    /**
     * The one configuration allowed to declare a {@link JwtDecoder}, and its nested classes. niord-base declares none:
     * Spring Boot builds it and adds every {@code OAuth2TokenValidator<Jwt>} bean, the {@code azp} check among them,
     * and a decoder declared elsewhere switches that off.
     */
    public static final String JWT_DECODER_CONFIGURATION =
            "org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerJwtConfiguration";

    /** The annotations that configure method security, which only niord-base's {@link SecurityConfig} carries */
    public static final List<String> METHOD_SECURITY_ANNOTATIONS = List.of(
            "org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity",
            "org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity");

    /**
     * The annotations that add a property file to the environment once the configuration classes are read, after the
     * property check ({@link #checkProperties}) has run, so that a protected property in that file would go unseen.
     * Only niord-base and the Spring framework may carry them.
     */
    public static final List<String> PROPERTY_SOURCE_ANNOTATIONS = List.of(
            "org.springframework.context.annotation.PropertySource",
            "org.springframework.context.annotation.PropertySources");

    /**
     * Beans that change what a method-security annotation admits (a role hierarchy widens every
     * {@code @RolesAllowed}), declared only by niord-base or Spring Security itself.
     */
    public static final List<Class<?>> METHOD_SECURITY_BEAN_TYPES =
            List.of(RoleHierarchy.class, MethodSecurityExpressionHandler.class);

    /**
     * CORS beans: CORS is configured nowhere (the frontends reach the backend on its own origin), so only the Spring
     * framework's own beans of these types are accepted (Spring MVC's {@code mvcHandlerMappingIntrospector} is one).
     */
    public static final List<Class<?>> CORS_BEAN_TYPES = List.of(CorsConfigurationSource.class, CorsFilter.class);

    /**
     * The classes a country jar may not carry a class of the same name for (docs/fe-country-split-plan.md §4.9: a base
     * product Java class is never replaced): the protected beans' classes, {@link SecurityConfig} and the guard.
     */
    public static final List<String> PROTECTED_CLASSES = List.of(
            SecurityConfig.class.getName(),
            WebAppConfig.class.getName(),
            NiordDomainFilter.class.getName(),
            DomainService.class.getName(),
            UserService.class.getName(),
            TicketService.class.getName(),
            SettingsService.class.getName(),
            ScriptResourceService.class.getName(),
            FmTemplateService.class.getName(),
            JsResourceService.class.getName(),
            MessageMailService.class.getName(),
            MailingListExecutionService.class.getName(),
            CountryContractGuard.class.getName(),
            CountryContractInitializer.class.getName());

    /**
     * The protected properties, as be/docs/country-api.md lists them. A country may not package them; a deployment
     * sets what it must in its own configuration.
     */
    public static final List<String> PROTECTED_PROPERTIES = List.of(
            "management.server.port",
            "management.endpoints.web.exposure.include",
            "management.endpoint.<id>.enabled",
            "management.endpoint.<id>.access",
            "management.endpoints.access.*",
            "management.endpoints.enabled-by-default",
            "management.endpoints.web.cors.*",
            "spring.security.*",
            "niord.oidc.base-client",
            "niord.oidc.allowed-clients");

    /** The one {@code spring.security.*} property a jar may set: where the tokens come from, for a local run */
    public static final String ISSUER_URI = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    /** The property that must give, beside {@code niord.oidc.allowed-clients}, why a jar widens the clients */
    public static final String ALLOWED_CLIENTS_REASON = "niord.oidc.allowed-clients-reason";

    private static final ConfigurationPropertyName MANAGEMENT_SERVER_PORT = name("management.server.port");
    private static final ConfigurationPropertyName EXPOSURE_INCLUDE = name("management.endpoints.web.exposure.include");
    private static final ConfigurationPropertyName ENDPOINTS_ACCESS = name("management.endpoints.access");
    private static final ConfigurationPropertyName ENABLED_BY_DEFAULT = name("management.endpoints.enabled-by-default");
    private static final ConfigurationPropertyName MANAGEMENT_CORS = name("management.endpoints.web.cors");
    private static final ConfigurationPropertyName SPRING_SECURITY = name("spring.security");
    private static final ConfigurationPropertyName ISSUER_URI_NAME = name(ISSUER_URI);
    private static final ConfigurationPropertyName BASE_CLIENT = name("niord.oidc.base-client");
    private static final ConfigurationPropertyName ALLOWED_CLIENTS = name("niord.oidc.allowed-clients");
    private static final ConfigurationPropertyName ALLOWED_CLIENTS_REASON_NAME = name(ALLOWED_CLIENTS_REASON);

    private CountryContractGuard() {
    }

    /**
     * Throws when there are findings, naming each on a line of its own, so that one start shows everything to fix.
     */
    public static void refuse(Collection<String> findings) {
        if (findings.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("Niord country contract: the start is refused, ")
                .append(findings.size()).append(findings.size() == 1 ? " finding" : " findings")
                .append(" (be/docs/country-api.md)");
        for (String finding : findings) {
            message.append(System.lineSeparator()).append(" - ").append(finding);
        }
        throw new IllegalStateException(message.toString());
    }

    // ------------------------------------------------------------------------------------------- //
    // Properties
    // ------------------------------------------------------------------------------------------- //

    /**
     * The protected properties a jar sets: every property source whose value comes from a class-path resource (a
     * packaged {@code application*.yml} or {@code .properties}, and niord-base's {@code niord-core-defaults.yml}).
     * Environment variables, system properties, the command line, {@code SPRING_APPLICATION_JSON} and {@code file:}
     * configuration (the Helm chart's {@code /config/application.yaml}) are the deployment's own configuration and are
     * not checked: a deployment sets its issuer, its allowed clients and its probes there.
     *
     * <p>The reason for {@code niord.oidc.allowed-clients} is looked for in the same file, whichever of its documents
     * holds it: Spring Boot makes one property source per {@code ---} document, so the file is found by the resource
     * each value's origin points into, not by the property source.
     */
    public static List<String> checkProperties(ConfigurableEnvironment environment) {
        Set<Resource> filesGivingAReason = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)
                    || !(source instanceof OriginLookup<?> lookup)) {
                continue;
            }
            for (String key : enumerable.getPropertyNames()) {
                Object value = enumerable.getProperty(key);
                Resource resource = ALLOWED_CLIENTS_REASON_NAME.equals(name(key))
                        ? classPathResource(origin(lookup, key)) : null;
                if (resource != null && value != null && !value.toString().isBlank()) {
                    filesGivingAReason.add(resource);
                }
            }
        }

        List<String> findings = new ArrayList<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)
                    || !(source instanceof OriginLookup<?> lookup)) {
                continue;
            }
            for (String key : enumerable.getPropertyNames()) {
                Resource resource = classPathResource(origin(lookup, key));
                if (resource == null) {
                    continue;
                }
                String refusal = propertyRefusal(key, enumerable.getProperty(key),
                        filesGivingAReason.contains(resource));
                if (refusal != null) {
                    findings.add("property " + key + " in " + resource.getDescription() + ": " + refusal);
                }
            }
        }
        return findings;
    }

    /** Why the given property may not come from a jar, or null when it may */
    static String propertyRefusal(String key, Object value, boolean allowedClientsReasonGiven) {
        ConfigurationPropertyName name = name(key);
        if (name == null) {
            return null;
        }
        if (MANAGEMENT_SERVER_PORT.equals(name)) {
            return "a protected property: it moves the actuator onto a management context that SecurityConfig does "
                    + "not guard";
        }
        if (EXPOSURE_INCLUDE.equals(name) || (name.isLastElementIndexed() && EXPOSURE_INCLUDE.isParentOf(name))) {
            for (String endpoint : String.valueOf(value).split(",")) {
                if (!endpoint.isBlank() && !"health".equals(endpoint.trim())) {
                    return "a protected property: it exposes the actuator endpoint '" + endpoint.trim()
                            + "' (only health may be exposed)";
                }
            }
            return null;
        }
        if (isEndpointSwitch(name) || ENDPOINTS_ACCESS.equals(name) || ENDPOINTS_ACCESS.isAncestorOf(name)
                || ENABLED_BY_DEFAULT.equals(name)) {
            return "a protected property: it changes which actuator endpoints are available";
        }
        if (MANAGEMENT_CORS.equals(name) || MANAGEMENT_CORS.isAncestorOf(name)) {
            return "a protected property: CORS is not configured anywhere";
        }
        if (SPRING_SECURITY.isAncestorOf(name) && !ISSUER_URI_NAME.equals(name)) {
            return "a protected property: Spring Security is configured by niord-base (a jar may set only "
                    + ISSUER_URI + ")";
        }
        if (BASE_CLIENT.equals(name)) {
            return "a protected property: the client whose roles form every caller's identity is niord-base's";
        }
        if ((ALLOWED_CLIENTS.equals(name) || (name.isLastElementIndexed() && ALLOWED_CLIENTS.isParentOf(name)))
                && !allowedClientsReasonGiven) {
            return "a protected property: it widens the clients whose tokens are accepted; set "
                    + ALLOWED_CLIENTS_REASON + " beside it to say why";
        }
        return null;
    }

    /** management.endpoint.&lt;id&gt;.enabled and management.endpoint.&lt;id&gt;.access, Spring Boot 4's name for it */
    private static boolean isEndpointSwitch(ConfigurationPropertyName name) {
        if (name.getNumberOfElements() != 4) {
            return false;
        }
        String last = name.getLastElement(ConfigurationPropertyName.Form.UNIFORM);
        return "management".equals(name.getElement(0, ConfigurationPropertyName.Form.UNIFORM))
                && "endpoint".equals(name.getElement(1, ConfigurationPropertyName.Form.UNIFORM))
                && ("enabled".equals(last) || "access".equals(last));
    }

    /** The given key as a property name, in any of the forms a property file may write it, or null */
    private static ConfigurationPropertyName name(String key) {
        ConfigurationPropertyName name = ConfigurationPropertyName.adapt(key, '.');
        return name.isEmpty() ? null : name;
    }

    private static Origin origin(OriginLookup<?> lookup, String key) {
        @SuppressWarnings("unchecked")
        OriginLookup<String> byKey = (OriginLookup<String>) lookup;
        return byKey.getOrigin(key);
    }

    /**
     * The class-path resource the given origin points into, or null when it comes from elsewhere. Spring Boot's
     * config data wraps the resource it read in an {@link OriginTrackedResource}.
     */
    private static Resource classPathResource(Origin origin) {
        for (Origin o = origin; o != null; o = o.getParent()) {
            if (o instanceof TextResourceOrigin text) {
                Resource resource = text.getResource();
                while (resource instanceof OriginTrackedResource tracked) {
                    resource = tracked.getResource();
                }
                if (resource instanceof ClassPathResource) {
                    return resource;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------- //
    // Bean definitions
    // ------------------------------------------------------------------------------------------- //

    /**
     * The protected beans declared outside niord-base. Called once every definition is registered and before any
     * bean is created, and again once the singletons exist, when each bean's type is its instance's. A bean's
     * declaring class is the class of its factory method, else its bean class. Token validators
     * ({@code OAuth2TokenValidator}) are not protected: they only narrow what is accepted.
     */
    public static List<String> checkBeanDefinitions(ConfigurableListableBeanFactory beanFactory) {
        Set<String> findings = new LinkedHashSet<>();

        for (Class<?> type : PROTECTED_BEAN_TYPES) {
            for (String bean : beanNames(beanFactory, type)) {
                Class<?> declaring = declaringClass(beanFactory, bean);
                if (!isBase(declaring)) {
                    findings.add(beanFinding(bean, type, declaring, "a protected bean, declared only by niord-base"));
                }
            }
        }

        for (String bean : beanNames(beanFactory, JwtDecoder.class)) {
            String declaring = declaringClassName(beanFactory, bean);
            if (declaring == null || !(declaring.equals(JWT_DECODER_CONFIGURATION)
                    || declaring.startsWith(JWT_DECODER_CONFIGURATION + "$"))) {
                findings.add("bean '" + bean + "' (JwtDecoder) declared by " + declaring + ": a decoder of its own "
                        + "switches off Spring Boot's, and with it the azp check; only Spring Boot's "
                        + "OAuth2ResourceServerJwtConfiguration declares one");
            }
        }

        for (Class<?> type : METHOD_SECURITY_BEAN_TYPES) {
            for (String bean : beanNames(beanFactory, type)) {
                Class<?> declaring = declaringClass(beanFactory, bean);
                if (!isBase(declaring) && !inPackage(declaring, "org.springframework.security.")) {
                    findings.add(beanFinding(bean, type, declaring,
                            "the method-security configuration is niord-base's"));
                }
            }
        }
        for (String bean : beanFactory.getBeanDefinitionNames()) {
            Class<?> beanClass = beanClass(beanFactory, bean);
            if (beanClass != null && !isBase(beanClass)) {
                for (String annotation : METHOD_SECURITY_ANNOTATIONS) {
                    if (MergedAnnotations.from(beanClass, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                            .isPresent(annotation)) {
                        findings.add("bean '" + bean + "' (" + beanClass.getName() + ") carries @"
                                + ClassUtils.getShortName(annotation)
                                + ": the method-security configuration is niord-base's");
                    }
                }
                // A @PropertySource is added to the environment after the property check has run, so a jar's
                // configuration goes where that check reads it
                if (!inPackage(beanClass, "org.springframework.")) {
                    for (String annotation : PROPERTY_SOURCE_ANNOTATIONS) {
                        if (MergedAnnotations.from(beanClass, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                                .isPresent(annotation)) {
                            findings.add("bean '" + bean + "' (" + beanClass.getName() + ") carries @"
                                    + ClassUtils.getShortName(annotation)
                                    + ": a jar's configuration goes in application*.yml, where the guard reads it");
                            break;
                        }
                    }
                }
            }
        }

        for (Class<?> type : CORS_BEAN_TYPES) {
            for (String bean : beanNames(beanFactory, type)) {
                Class<?> declaring = declaringClass(beanFactory, bean);
                if (!inPackage(declaring, "org.springframework.")) {
                    findings.add(beanFinding(bean, type, declaring, "CORS is not configured anywhere"));
                }
            }
        }
        for (String bean : beanNames(beanFactory, WebMvcConfigurer.class)) {
            Class<?> type = beanFactory.getType(bean, false);
            if (type != null && !isBase(type) && !inPackage(type, "org.springframework.")
                    && declaresCorsMappings(ClassUtils.getUserClass(type))) {
                findings.add("bean '" + bean + "' (WebMvcConfigurer) " + ClassUtils.getUserClass(type).getName()
                        + " declares addCorsMappings: CORS is not configured anywhere");
            }
        }
        return new ArrayList<>(findings);
    }

    private static String beanFinding(String bean, Class<?> type, Class<?> declaring, String why) {
        return "bean '" + bean + "' (" + type.getSimpleName() + ") declared by "
                + (declaring == null ? "an unknown class" : declaring.getName()) + ": " + why;
    }

    /** Every bean of the given type, its definitions' predicted types and the singletons' actual ones */
    private static List<String> beanNames(ConfigurableListableBeanFactory beanFactory, Class<?> type) {
        List<String> names = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(type, true, false)) {
            names.add(BeanFactoryUtils.transformedBeanName(name));
        }
        return names;
    }

    /** The class whose code declares the given bean: its factory method's class, else its bean class */
    static Class<?> declaringClass(ConfigurableListableBeanFactory beanFactory, String bean) {
        String name = declaringClassName(beanFactory, bean);
        return name == null ? null : load(name, beanFactory.getBeanClassLoader());
    }

    private static String declaringClassName(ConfigurableListableBeanFactory beanFactory, String bean) {
        if (beanFactory.containsBeanDefinition(bean)) {
            BeanDefinition definition = beanFactory.getBeanDefinition(bean);
            if (definition instanceof AnnotatedBeanDefinition annotated
                    && annotated.getFactoryMethodMetadata() != null) {
                return annotated.getFactoryMethodMetadata().getDeclaringClassName();
            }
            BeanDefinition merged = beanFactory.getMergedBeanDefinition(bean);
            if (merged instanceof RootBeanDefinition root && root.getResolvedFactoryMethod() != null) {
                return ClassUtils.getUserClass(root.getResolvedFactoryMethod().getDeclaringClass()).getName();
            }
            if (merged.getFactoryMethodName() != null && merged.getFactoryBeanName() != null) {
                Class<?> factory = beanFactory.getType(merged.getFactoryBeanName(), false);
                return factory == null ? null : ClassUtils.getUserClass(factory).getName();
            }
            if (merged instanceof AbstractBeanDefinition abstractDefinition && abstractDefinition.hasBeanClass()) {
                return ClassUtils.getUserClass(abstractDefinition.getBeanClass()).getName();
            }
            if (merged.getBeanClassName() != null) {
                return merged.getBeanClassName();
            }
        }
        Class<?> type = beanFactory.getType(bean, false);
        return type == null ? null : ClassUtils.getUserClass(type).getName();
    }

    /** The bean class of a definition without a factory method (a configuration or a component), or null */
    private static Class<?> beanClass(ConfigurableListableBeanFactory beanFactory, String bean) {
        BeanDefinition merged;
        try {
            merged = beanFactory.getMergedBeanDefinition(bean);
        } catch (RuntimeException e) {
            return null;
        }
        if (merged.getFactoryMethodName() != null || merged.getBeanClassName() == null) {
            return null;
        }
        Class<?> beanClass = load(merged.getBeanClassName(), beanFactory.getBeanClassLoader());
        return beanClass == null ? null : ClassUtils.getUserClass(beanClass);
    }

    private static boolean declaresCorsMappings(Class<?> type) {
        Method method = ReflectionUtils.findMethod(type, "addCorsMappings", CorsRegistry.class);
        return method != null && method.getDeclaringClass() != WebMvcConfigurer.class;
    }

    private static boolean inPackage(Class<?> type, String prefix) {
        return type != null && type.getName().startsWith(prefix);
    }

    private static Class<?> load(String name, ClassLoader classLoader) {
        try {
            return ClassUtils.forName(name, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Handlers
    // ------------------------------------------------------------------------------------------- //

    /**
     * The handler methods that would be public by omission. Every handler must resolve {@code @PermitAll},
     * {@code @RolesAllowed} or {@code @DenyAll} on the method or on the class declaring it, resolved with
     * {@link #JSR250} on (method, bean type) exactly as {@code Jsr250AuthorizationManager} resolves it; competing
     * annotations are a finding, as they fail at runtime. A handler with {@code @CrossOrigin} is refused: CORS is
     * configured nowhere. The handlers of {@link #REVIEWED_FRAMEWORK_HANDLERS} are exempt. Role names are not checked
     * here (a misspelt role fails closed; {@code RestControllerSecurityAnnotationTest} checks them in niord-base).
     */
    public static List<String> checkHandlers(Collection<HandlerMethod> handlers) {
        Set<String> findings = new TreeSet<>();
        for (HandlerMethod handler : handlers) {
            Class<?> beanType = ClassUtils.getUserClass(handler.getBeanType());
            if (REVIEWED_FRAMEWORK_HANDLERS.contains(beanType.getName())) {
                continue;
            }
            Method method = handler.getMethod();
            String name = "handler " + beanType.getName() + "." + method.getName();
            if (AnnotatedElementUtils.findMergedAnnotation(method, CrossOrigin.class) != null
                    || AnnotatedElementUtils.findMergedAnnotation(beanType, CrossOrigin.class) != null) {
                findings.add(name + ": @CrossOrigin, and CORS is not configured anywhere");
            }
            try {
                if (JSR250.scan(method, beanType) == null) {
                    findings.add(name + ": no @PermitAll, @RolesAllowed or @DenyAll on the method or the class "
                            + "declaring it, so it would be public by omission");
                }
            } catch (RuntimeException e) {
                findings.add(name + ": " + e.getMessage());
            }
        }
        return new ArrayList<>(findings);
    }

    // ------------------------------------------------------------------------------------------- //
    // Class origins
    // ------------------------------------------------------------------------------------------- //

    /** {@link #checkClassOrigins(ClassLoader)} with the guard's own class loader */
    public static List<String> checkClassOrigins() {
        return checkClassOrigins(CountryContractGuard.class.getClassLoader());
    }

    /**
     * The protected classes that the class path holds more than once or that load from another code source than
     * niord-base: a same-name class in a country jar would replace niord-base's, and which copy wins depends on the
     * class path (docs/fe-country-split-plan.md §4.9).
     */
    public static List<String> checkClassOrigins(ClassLoader classLoader) {
        List<String> findings = new ArrayList<>();
        for (String className : PROTECTED_CLASSES) {
            String path = className.replace('.', '/') + ".class";
            List<URL> copies;
            try {
                copies = Collections.list(classLoader.getResources(path));
            } catch (IOException e) {
                findings.add("class " + className + ": the class path could not be read (" + e.getMessage() + ")");
                continue;
            }
            if (copies.size() > 1) {
                findings.add("class " + className + " is on the class path " + copies.size() + " times ("
                        + copies + "): a niord-base class is never replaced");
                continue;
            }
            Class<?> loaded = load(className, classLoader);
            if (loaded != null && !isBase(loaded)) {
                findings.add("class " + className + " loads from " + location(loaded)
                        + ", not from niord-base: a niord-base class is never replaced");
            }
        }
        return findings;
    }

    /** Whether the given class loads from niord-base, the code source of the guard itself */
    static boolean isBase(Class<?> type) {
        return type != null && Objects.equals(location(type), location(CountryContractGuard.class));
    }

    private static String location(Class<?> type) {
        ProtectionDomain domain = type.getProtectionDomain();
        CodeSource source = domain == null ? null : domain.getCodeSource();
        return source == null || source.getLocation() == null ? null : source.getLocation().toExternalForm();
    }
}
