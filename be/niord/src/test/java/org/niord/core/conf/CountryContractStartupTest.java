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

import org.example.country.CountryJwtDecoderConfiguration;
import org.example.country.CountryMinimalConfiguration;
import org.example.country.CountryPropertySourceConfiguration;
import org.example.country.CountrySecurityConfiguration;
import org.example.country.OpenCountryController;
import org.example.country.OpenCountryWebConfiguration;
import org.junit.jupiter.api.Test;
import org.niord.test.CountryBuildGroup;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start-up guard end to end: a {@link SpringApplication} of a country's fixture configuration and nothing else
 * (no web server, except for the handler rule, which needs Spring MVC's handler mappings), so that it is the
 * registration in niord-base's {@code META-INF/spring.factories} that brings {@link CountryContractInitializer} in, as
 * it does for a country's application, whatever it scans. The guard's rules are {@link CountryContractGuardTest}'s;
 * this asserts only that a start fails naming what it found, and that an application without a finding starts with
 * the guard in it.
 */
@CountryBuildGroup
class CountryContractStartupTest {

    @Test
    void aProtectedPropertyInAJarStopsTheStart() {
        String message = failedStart(CountryMinimalConfiguration.class,
                "--spring.config.location=classpath:/country-contract/management-port.yml");

        assertTrue(message.contains("property management.server.port in class path resource "
                + "[country-contract/management-port.yml]"), message);
    }

    /**
     * A property file brought in by {@code @PropertySource} reaches the environment after the property check has run,
     * so the annotation itself is refused
     */
    @Test
    void aCountrysPropertySourceStopsTheStart() {
        String message = failedStart(CountryPropertySourceConfiguration.class);

        assertTrue(message.contains("bean 'countryPropertySourceConfiguration' ("
                + CountryPropertySourceConfiguration.class.getName() + ") carries @PropertySource: a jar's "
                + "configuration goes in application*.yml, where the guard reads it"), message);
    }

    @Test
    void aCountrysSecurityFilterChainStopsTheStartBeforeAnyBeanIsCreated() {
        String message = failedStart(CountrySecurityConfiguration.class);

        assertTrue(message.contains("bean 'countrySecurityFilterChain' (SecurityFilterChain) declared by "
                + CountrySecurityConfiguration.class.getName()), message);
    }

    @Test
    void aCountrysJwtDecoderStopsTheStart() {
        String message = failedStart(CountryJwtDecoderConfiguration.class);

        assertTrue(message.contains("bean 'countryJwtDecoder' (JwtDecoder) declared by "
                + CountryJwtDecoderConfiguration.class.getName()), message);
    }

    @Test
    void anUnannotatedControllerOutsideOrgNiordStopsTheStartBeforeTheServerListens() {
        String message = failedStart(WebApplicationType.SERVLET, OpenCountryWebConfiguration.class);

        assertTrue(message.contains("handler " + OpenCountryController.class.getName()
                + ".open: no @PermitAll, @RolesAllowed or @DenyAll"), message);
    }

    @Test
    void anApplicationWithoutAFindingStartsWithTheGuard() {
        try (ConfigurableApplicationContext context = application(WebApplicationType.NONE,
                CountryMinimalConfiguration.class)
                .run("--spring.config.location=classpath:/country-contract/allowed.yml")) {
            assertTrue(context.containsBean(CountryContractGuard.RUNTIME_CHECK_BEAN),
                    "the runtime check is registered through spring.factories");
        }
    }

    private static SpringApplication application(WebApplicationType type, Class<?> source) {
        SpringApplication application = new SpringApplication(source);
        application.setWebApplicationType(type);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);
        return application;
    }

    /** The messages of the failed start's exception and its causes */
    private static String failedStart(Class<?> source, String... args) {
        return failedStart(WebApplicationType.NONE, source, args);
    }

    private static String failedStart(WebApplicationType type, Class<?> source, String... args) {
        Throwable failure = assertThrows(Throwable.class, () -> application(type, source).run(args).close());
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains("Niord country contract: the start is refused"), messages.toString());
        return messages.toString();
    }
}
