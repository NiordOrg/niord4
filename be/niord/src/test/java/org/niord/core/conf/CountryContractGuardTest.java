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

import org.example.country.CountryBeans;
import org.example.country.CountryCorsConfigurer;
import org.example.country.CountryDomainService;
import org.example.country.CountryHandlers;
import org.example.country.CountryMethodSecurity;
import org.example.country.CountryPropertySourceConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.niord.test.CountryBuildGroup;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedResource;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.HandlerMethod;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * The start-up guard's rules, one negative test each, and what it accepts ({@link CountryContractGuard},
 * docs/fe-country-split-plan.md §4.11 rule 3). Each check is called directly: on an environment made of property
 * sources, on a bean factory of hand-made definitions, on hand-made handler methods. The fixtures are a country's
 * classes in {@code org.example.country}: in this module they load from the test classes, in a country build from
 * niord-base's test-jar, and either way not from niord-base's main code, which is what the guard asks.
 */
@CountryBuildGroup
class CountryContractGuardTest {

    // ------------------------------------------------------------------------------------------- //
    // Properties
    // ------------------------------------------------------------------------------------------- //

    /** Each protected key, with a value that breaks its rule, as a jar's configuration would set it */
    private static final Map<String, String> PROTECTED = new LinkedHashMap<>();

    static {
        PROTECTED.put("management.server.port", "9001");
        PROTECTED.put("management.endpoints.web.exposure.include", "health,env");
        PROTECTED.put("management.endpoints.web.exposure.include[1]", "env");
        PROTECTED.put("management.endpoint.env.enabled", "true");
        PROTECTED.put("management.endpoint.env.access", "unrestricted");
        PROTECTED.put("management.endpoints.access.default", "unrestricted");
        PROTECTED.put("management.endpoints.enabled-by-default", "true");
        PROTECTED.put("management.endpoints.web.cors.allowed-origins", "https://example.org");
        PROTECTED.put("spring.security.user.name", "country");
        PROTECTED.put("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "http://localhost/certs");
        PROTECTED.put("niord.oidc.base-client", "niord-client-country");
        PROTECTED.put("niord.oidc.allowed-clients", "niord-web,country-script");
    }

    @Test
    void everyProtectedPropertyFromAJarIsRefusedAndNamed() {
        for (Map.Entry<String, String> property : PROTECTED.entrySet()) {
            List<String> findings = CountryContractGuard.checkProperties(environment(
                    jarSource("application.yml", Map.of(property.getKey(), property.getValue()))));

            assertEquals(1, findings.size(), property.getKey() + ": " + findings);
            assertTrue(findings.getFirst().startsWith("property " + property.getKey() + " in class path resource "
                    + "[application.yml]: a protected property"), findings.getFirst());
        }
    }

    @Test
    void theFixtureFilesAreRefusedEachNamingItsProperty() throws IOException {
        Map<String, String> fixtures = Map.of(
                "management-port.yml", "management.server.port",
                "exposure-env.yml", "management.endpoints.web.exposure.include",
                "spring-security-user.yml", "spring.security.user.name",
                "jwk-set-uri.yml", "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                "base-client.yml", "niord.oidc.base-client",
                "allowed-clients.yml", "niord.oidc.allowed-clients");
        for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
            List<String> findings = CountryContractGuard.checkProperties(
                    environment(yaml(new ClassPathResource("country-contract/" + fixture.getKey()))));

            assertEquals(List.of("property " + fixture.getValue()), findings.stream()
                    .map(f -> f.substring(0, f.indexOf(" in "))).toList(), fixture.getKey() + ": " + findings);
            assertTrue(findings.getFirst().contains("class path resource [country-contract/" + fixture.getKey() + "]"),
                    findings.getFirst());
        }
    }

    @Test
    void theDeploymentsOwnConfigurationIsNotChecked(@TempDir Path dir) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>(PROTECTED);
        PropertySource<?> systemProperties = new MapPropertySource("systemProperties", values);

        Path file = dir.resolve("application.yml");
        try (InputStream in = new ClassPathResource("country-contract/management-port.yml").getInputStream()) {
            Files.copy(in, file);
        }
        // A file: location (the Helm chart's /config/application.yaml): origin-tracked, but not on the class path
        PropertySource<?> fileConfig = yaml(new FileSystemResource(file));

        assertEquals(List.of(), CountryContractGuard.checkProperties(environment(systemProperties, fileConfig)));
    }

    @Test
    void theIssuerTheAllowedClientsWithAReasonAndHealthAreAccepted() throws IOException {
        assertEquals(List.of(), CountryContractGuard.checkProperties(
                environment(yaml(new ClassPathResource("country-contract/allowed.yml")))));
        assertEquals(List.of(), CountryContractGuard.checkProperties(environment(jarSource("application.yml",
                Map.of("management.endpoints.web.exposure.include", "health",
                        "management.endpoints.web.exposure.exclude", "env",
                        "management.endpoint.health.probes.enabled", "true",
                        "management.endpoints.web.base-path", "/rest/actuator")))));
    }

    @Test
    void theReasonForTheAllowedClientsMustNotBeBlank() {
        List<String> findings = CountryContractGuard.checkProperties(environment(jarSource("application.yml",
                Map.of("niord.oidc.allowed-clients", "niord-web,country-script",
                        "niord.oidc.allowed-clients-reason", " "))));

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.getFirst().contains(CountryContractGuard.ALLOWED_CLIENTS_REASON), findings.getFirst());
    }

    @Test
    void theReasonMayBeInAnotherDocumentOfTheSameFileButNotInAnotherFile() {
        // Spring Boot makes one property source per --- document of a file
        PropertySource<?> clients = jarSource("application.yml (document #0)", "application.yml",
                Map.of("niord.oidc.allowed-clients", "niord-web,country-script"));
        PropertySource<?> reasonInTheSameFile = jarSource("application.yml (document #1)", "application.yml",
                Map.of("niord.oidc.allowed-clients-reason", "the country's import script"));
        PropertySource<?> reasonInAnotherFile = jarSource("application-country.yml", "application-country.yml",
                Map.of("niord.oidc.allowed-clients-reason", "the country's import script"));

        assertEquals(List.of(), CountryContractGuard.checkProperties(environment(clients, reasonInTheSameFile)));

        List<String> findings = CountryContractGuard.checkProperties(environment(clients, reasonInAnotherFile));
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.getFirst().startsWith("property niord.oidc.allowed-clients in class path resource "
                + "[application.yml]"), findings.getFirst());
    }

    @Test
    void niordBasesOwnDefaultsPass() throws IOException {
        assertEquals(List.of(), CountryContractGuard.checkProperties(
                environment(yaml(new ClassPathResource("config/niord-core-defaults.yml")))));
    }

    // ------------------------------------------------------------------------------------------- //
    // Beans
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aSubclassOfAProtectedServiceIsRefused() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("domainService", new RootBeanDefinition(CountryDomainService.class));

        assertEquals(List.of("bean 'domainService' (DomainService) declared by " + CountryDomainService.class.getName()
                        + ": a protected bean, declared only by niord-base"),
                CountryContractGuard.checkBeanDefinitions(beanFactory));
    }

    @Test
    void niordBasesClassFromACountrysFactoryMethodIsRefused() {
        assertRefused("domainService", "DomainService");
    }

    @Test
    void aCountrysSecurityFilterChainIsRefused() {
        assertRefused("securityFilterChain", "SecurityFilterChain");
    }

    @Test
    void aCountrysJwtAuthenticationConverterIsRefused() {
        assertRefused("jwtAuthenticationConverter", "JwtAuthenticationConverter");
    }

    @Test
    void aCountrysGrantedAuthorityDefaultsIsRefused() {
        assertRefused("grantedAuthorityDefaults", "GrantedAuthorityDefaults");
    }

    @Test
    void aCountrysJwtDecoderIsRefused() {
        assertRefused("jwtDecoder", "JwtDecoder");
    }

    @Test
    void aCountrysRoleHierarchyIsRefused() {
        assertRefused("roleHierarchy", "RoleHierarchy");
    }

    @Test
    void aCountrysCorsConfigurationSourceIsRefused() {
        assertRefused("corsConfigurationSource", "CorsConfigurationSource");
    }

    @Test
    void aCountrysMethodSecurityConfigurationIsRefused() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("countryMethodSecurity",
                new RootBeanDefinition(CountryMethodSecurity.class));

        List<String> findings = CountryContractGuard.checkBeanDefinitions(beanFactory);
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.getFirst().startsWith("bean 'countryMethodSecurity' ("
                + CountryMethodSecurity.class.getName() + ") carries @EnableMethodSecurity"), findings.getFirst());
    }

    @Test
    void aCountrysPropertySourceIsRefused() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("countryPropertySource",
                new RootBeanDefinition(CountryPropertySourceConfiguration.class));

        List<String> findings = CountryContractGuard.checkBeanDefinitions(beanFactory);
        assertEquals(List.of("bean 'countryPropertySource' (" + CountryPropertySourceConfiguration.class.getName()
                + ") carries @PropertySource: a jar's configuration goes in application*.yml, where the guard reads "
                + "it"), findings);
    }

    @Test
    void aCountrysCorsMappingsAreRefused() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("countryCors", new RootBeanDefinition(CountryCorsConfigurer.class));

        List<String> findings = CountryContractGuard.checkBeanDefinitions(beanFactory);
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.getFirst().startsWith("bean 'countryCors' (WebMvcConfigurer) "
                + CountryCorsConfigurer.class.getName() + " declares addCorsMappings"), findings.getFirst());
    }

    @Test
    void everyProtectedTypeIsChecked() {
        // One bean of each protected type, declared by a country's class: every one is refused
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        int i = 0;
        for (Class<?> type : CountryContractGuard.PROTECTED_BEAN_TYPES) {
            RootBeanDefinition definition = new RootBeanDefinition(type);
            definition.setFactoryBeanName("countryBeans");
            definition.setFactoryMethodName("domainService");
            definition.setTargetType(type);
            beanFactory.registerBeanDefinition("protected" + i++, definition);
        }
        beanFactory.registerBeanDefinition("countryBeans", new RootBeanDefinition(CountryBeans.class));

        List<String> findings = CountryContractGuard.checkBeanDefinitions(beanFactory);
        for (Class<?> type : CountryContractGuard.PROTECTED_BEAN_TYPES) {
            assertTrue(findings.stream().anyMatch(f -> f.contains("(" + type.getSimpleName() + ") declared by "
                    + CountryBeans.class.getName())), type.getSimpleName() + " not refused: " + findings);
        }
    }

    @Test
    void niordBasesOwnClassesAndATokenValidatorAreAccepted() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        for (Class<?> type : List.of(DomainService.class, UserService.class, TicketService.class,
                SettingsService.class, ScriptResourceService.class, FmTemplateService.class, JsResourceService.class,
                MessageMailService.class, MailingListExecutionService.class, NiordDomainFilter.class,
                WebAppConfig.class)) {
            beanFactory.registerBeanDefinition(type.getSimpleName(), new RootBeanDefinition(type));
        }
        // SecurityConfig's own factory methods
        beanFactory.registerBeanDefinition("securityConfig", new RootBeanDefinition(SecurityConfig.class));
        beanFactory.registerBeanDefinition("securityFilterChain", factoryBean("securityConfig", "securityFilterChain",
                SecurityFilterChain.class));
        beanFactory.registerBeanDefinition("jwtAuthConverter", factoryBean("securityConfig", "jwtAuthConverter",
                JwtAuthenticationConverter.class));
        // A country may add token validators: they only narrow
        beanFactory.registerBeanDefinition("countryBeans", new RootBeanDefinition(CountryBeans.class));
        beanFactory.registerBeanDefinition("tokenValidator", factoryBean("countryBeans", "tokenValidator", null));

        assertEquals(List.of(), CountryContractGuard.checkBeanDefinitions(beanFactory));
    }

    @Test
    void springBootsJwtDecoderIsAccepted() throws ReflectiveOperationException {
        // Spring Boot declares the decoder in a nested class of OAuth2ResourceServerJwtConfiguration
        Class<?> nested = Class.forName(CountryContractGuard.JWT_DECODER_CONFIGURATION + "$JwtDecoderConfiguration");
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("jwtDecoderByIssuerUri", decoder(decoderMethod(nested)));
        assertEquals(List.of(), CountryContractGuard.checkBeanDefinitions(beanFactory));

        // The same definition made by a country's method is refused, so the bean was found
        beanFactory.registerBeanDefinition("countryJwtDecoder",
                decoder(CountryBeans.class.getMethod("jwtDecoder")));
        List<String> findings = CountryContractGuard.checkBeanDefinitions(beanFactory);
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.getFirst().startsWith("bean 'countryJwtDecoder' (JwtDecoder) declared by "
                + CountryBeans.class.getName()), findings.getFirst());
    }

    private static RootBeanDefinition decoder(Method factoryMethod) {
        RootBeanDefinition decoder = new RootBeanDefinition(JwtDecoder.class);
        decoder.setResolvedFactoryMethod(factoryMethod);
        decoder.setTargetType(JwtDecoder.class);
        return decoder;
    }

    /** The method of Spring Boot's configuration that makes a decoder */
    private static Method decoderMethod(Class<?> configuration) {
        for (Method method : configuration.getDeclaredMethods()) {
            if (JwtDecoder.class.isAssignableFrom(method.getReturnType())) {
                return method;
            }
        }
        return fail("no JwtDecoder method in " + configuration.getName());
    }

    private static void assertRefused(String factoryMethod, String typeName) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("countryBeans", new RootBeanDefinition(CountryBeans.class));
        beanFactory.registerBeanDefinition(factoryMethod, factoryBean("countryBeans", factoryMethod, null));

        List<String> findings = CountryContractGuard.checkBeanDefinitions(beanFactory);
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.getFirst().startsWith("bean '" + factoryMethod + "' (" + typeName + ") declared by "
                + CountryBeans.class.getName() + ": "), findings.getFirst());
    }

    private static RootBeanDefinition factoryBean(String factoryBean, String factoryMethod, Class<?> targetType) {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setFactoryBeanName(factoryBean);
        definition.setUniqueFactoryMethodName(factoryMethod);
        if (targetType != null) {
            definition.setTargetType(targetType);
        }
        return definition;
    }

    // ------------------------------------------------------------------------------------------- //
    // Handlers
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anUnannotatedHandlerIsRefused() throws NoSuchMethodException {
        assertEquals(List.of("handler " + CountryHandlers.Unannotated.class.getName() + ".unannotated: no "
                        + "@PermitAll, @RolesAllowed or @DenyAll on the method or the class declaring it, so it "
                        + "would be public by omission"),
                CountryContractGuard.checkHandlers(List.of(handler(new CountryHandlers.Unannotated(),
                        "unannotated"))));
    }

    @Test
    void anInheritedUnannotatedHandlerIsRefusedDespiteTheSubclassAnnotation() throws NoSuchMethodException {
        CountryHandlers.InheritsUnannotated bean = new CountryHandlers.InheritsUnannotated();
        List<String> findings = CountryContractGuard.checkHandlers(
                List.of(handler(bean, "inherited"), handler(bean, "own")));

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.getFirst().startsWith("handler " + CountryHandlers.InheritsUnannotated.class.getName()
                + ".inherited: no @PermitAll"), findings.getFirst());
    }

    @Test
    void competingAnnotationsAreRefused() throws NoSuchMethodException {
        List<String> findings = CountryContractGuard.checkHandlers(
                List.of(handler(new CountryHandlers.Competing(), "competing")));

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.getFirst().startsWith("handler " + CountryHandlers.Competing.class.getName()
                + ".competing: "), findings.getFirst());
    }

    @Test
    void aCrossOriginHandlerIsRefused() throws NoSuchMethodException {
        assertEquals(List.of("handler " + CountryHandlers.CrossOriginHandler.class.getName() + ".crossOrigin: "
                        + "@CrossOrigin, and CORS is not configured anywhere"),
                CountryContractGuard.checkHandlers(List.of(handler(new CountryHandlers.CrossOriginHandler(),
                        "crossOrigin"))));
    }

    @Test
    void anAnnotatedHandlerAndTheReviewedFrameworkHandlersAreAccepted() throws NoSuchMethodException {
        List<HandlerMethod> handlers = new ArrayList<>();
        handlers.add(handler(new CountryHandlers.Annotated(), "annotated"));
        BasicErrorController errorController = mock(BasicErrorController.class);
        for (Method method : BasicErrorController.class.getDeclaredMethods()) {
            handlers.add(new HandlerMethod(errorController, method));
        }
        assertEquals(BasicErrorController.class, handlers.getLast().getBeanType());

        assertEquals(List.of(), CountryContractGuard.checkHandlers(handlers));
    }

    private static HandlerMethod handler(Object bean, String method) throws NoSuchMethodException {
        return new HandlerMethod(bean, bean.getClass().getMethod(method));
    }

    // ------------------------------------------------------------------------------------------- //
    // Class origins
    // ------------------------------------------------------------------------------------------- //

    @Test
    void niordBasesProtectedClassesLoadFromNiordBase() {
        assertEquals(List.of(), CountryContractGuard.checkClassOrigins());
    }

    @Test
    void aClassFromAnotherCodeSourceIsNotNiordBases() {
        assertTrue(CountryContractGuard.isBase(SecurityConfig.class));
        assertFalse(CountryContractGuard.isBase(CountryDomainService.class));
        assertFalse(CountryContractGuard.isBase(String.class));
    }

    @Test
    void refuseNamesEveryFinding() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> CountryContractGuard.refuse(List.of("one", "two")));

        assertTrue(refused.getMessage().startsWith("Niord country contract: the start is refused, 2 findings"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains(" - one") && refused.getMessage().contains(" - two"),
                refused.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //

    private static StandardEnvironment environment(PropertySource<?>... sources) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> source : sources) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    /**
     * A property source as Spring Boot's config data reads a jar's configuration: every value tracked to the
     * class-path resource, which config data wraps in an {@link OriginTrackedResource}
     */
    private static PropertySource<?> jarSource(String resource, Map<String, String> values) {
        return jarSource("Config resource 'class path resource [" + resource + "]'", resource, values);
    }

    private static PropertySource<?> jarSource(String sourceName, String resource, Map<String, String> values) {
        Resource wrapped = OriginTrackedResource.of(new ClassPathResource(resource), null);
        Map<String, Object> tracked = new LinkedHashMap<>();
        values.forEach((key, value) -> tracked.put(key,
                OriginTrackedValue.of(value, new TextResourceOrigin(wrapped, null))));
        return new OriginTrackedMapPropertySource(sourceName, tracked);
    }

    private static PropertySource<?> yaml(Resource resource) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(resource.getDescription(), resource);
        assertEquals(1, sources.size(), resource.getDescription());
        return sources.getFirst();
    }
}
