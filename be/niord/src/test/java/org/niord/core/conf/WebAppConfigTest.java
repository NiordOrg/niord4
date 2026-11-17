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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.settings.SettingsService;
import org.niord.test.CountryBuildGroup;
import org.niord.web.WebAppFallbackFilter;
import org.niord.web.WebAppHeadersFilter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link WebAppConfig} — whether a deployment serves the web app at all, and where its
 * fallback filter and its headers filter sit.
 *
 * <p>The web app is served only when {@code niord.web-app.dir} names a directory that holds the build's
 * {@code index.html}. Unset, blank, or naming an empty or missing directory (an image built without the web
 * app), nothing is registered and the backend's root keeps answering 404. When it is served, the fallback
 * filter runs after the Spring Security filter chain, so the page it forwards to carries the security headers, and
 * the headers filter runs between the two, so the forwarded page carries the app's Content-Security-Policy.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than a full {@code @SpringBootTest}: no database, no
 * containers, so it runs under Surefire — the same approach as {@link AdminAppRedirectWebConfigTest}.
 */
@CountryBuildGroup
public class WebAppConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // A ready singleton, not a bean definition: the mock inherits the service's @Inject fields, which
            // would otherwise be injected and ask for a database
            .withInitializer(context -> context.getBeanFactory()
                    .registerSingleton("settingsService", mock(SettingsService.class)))
            .withUserConfiguration(WebAppConfig.class);

    @Test
    public void registersNothingWithoutTheProperty() {
        runner.run(context -> {
            assertFalse(context.containsBean("webAppFallbackFilter"),
                    "a deployment without a web app must not forward navigations");
            assertFalse(context.containsBean("webAppHeadersFilter"), "no app, no headers of the app");
            assertTrue(context.getBeansOfType(WebAppConfig.class).isEmpty());
        });
    }

    @Test
    public void registersNothingWhenThePropertyIsBlank() {
        runner.withPropertyValues("niord.web-app.dir=   ").run(context -> {
            assertFalse(context.containsBean("webAppFallbackFilter"),
                    "a blank directory must count as no web app");
            assertFalse(context.containsBean("webAppHeadersFilter"), "no app, no headers of the app");
            assertTrue(context.getBeansOfType(WebAppConfig.class).isEmpty());
        });
    }

    @Test
    public void registersTheHeadersAndFallbackFiltersAfterSpringSecurity(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("index.html"), "<!doctype html><title>Niord</title>");

        runner.withPropertyValues("niord.web-app.dir=" + dir).run(context -> {
            assertTrue(context.containsBean("webAppFallbackFilter"));
            FilterRegistrationBean<?> reg = context.getBean("webAppFallbackFilter", FilterRegistrationBean.class);
            assertInstanceOf(WebAppFallbackFilter.class, reg.getFilter());
            assertEquals(List.of("/*"), List.copyOf(reg.getUrlPatterns()));
            assertTrue(reg.getOrder() > WebAppConfig.SPRING_SECURITY_FILTER_ORDER,
                    "the filter must run after the Spring Security filter chain, was " + reg.getOrder());

            FilterRegistrationBean<?> headers = context.getBean("webAppHeadersFilter", FilterRegistrationBean.class);
            assertInstanceOf(WebAppHeadersFilter.class, headers.getFilter());
            assertEquals(List.of("/*"), List.copyOf(headers.getUrlPatterns()));
            assertTrue(headers.getOrder() > WebAppConfig.SPRING_SECURITY_FILTER_ORDER
                            && headers.getOrder() < reg.getOrder(),
                    "the headers filter must run after the Spring Security filter chain and before the fallback, was "
                            + headers.getOrder());
        });
    }

    @Test
    public void registersTheHeadersFilterWithoutResolvingTheSettingsService(@TempDir Path dir) throws Exception {
        // The embedded web server creates the filter registrations before the persistence layer exists, and the
        // settings service needs its entity manager. Here the service does not exist at all: asking for it at
        // creation fails the start, as it stopped the backend before the filter took a provider.
        Files.writeString(dir.resolve("index.html"), "<!doctype html><title>Niord</title>");

        new ApplicationContextRunner().withUserConfiguration(WebAppConfig.class)
                .withPropertyValues("niord.web-app.dir=" + dir)
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "creating the headers filter must not resolve the settings service");
                    assertInstanceOf(WebAppHeadersFilter.class,
                            context.getBean("webAppHeadersFilter", FilterRegistrationBean.class).getFilter());
                });
    }

    @Test
    public void registersNothingWhenTheDirectoryHoldsNoApp(@TempDir Path dir) throws Exception {
        // An image built without the web app: the Dockerfile still sets the property. The context starts,
        // and no navigation is forwarded to an index.html that does not exist.
        Files.writeString(dir.resolve("other.html"), "<!doctype html><title>not the app</title>");

        runner.withPropertyValues("niord.web-app.dir=" + dir).run(context -> {
            assertNull(context.getStartupFailure(), "a directory without index.html must not stop the backend");
            assertFalse(context.containsBean("webAppFallbackFilter"),
                    "without index.html the fallback filter would forward every navigation to a 404");
            assertFalse(context.containsBean("webAppHeadersFilter"), "no app, no headers of the app");
            assertTrue(context.getBeansOfType(WebAppConfig.class).isEmpty());
        });
    }

    @Test
    public void registersNothingWhenThePropertyIsNoValidPath() {
        // A NUL character is not a valid path on any platform. The backend still starts, without the web app.
        runner.withPropertyValues("niord.web-app.dir=/app/w" + '\0' + "eb").run(context -> {
            assertNull(context.getStartupFailure(), "an invalid path must not stop the backend");
            assertFalse(context.containsBean("webAppFallbackFilter"));
            assertFalse(context.containsBean("webAppHeadersFilter"), "no app, no headers of the app");
            assertTrue(context.getBeansOfType(WebAppConfig.class).isEmpty());
        });
    }

    @Test
    public void registersNothingWhenTheDirectoryIsMissing(@TempDir Path dir) {
        // A property that names a directory that does not exist, as a misconfigured deployment might.
        runner.withPropertyValues("niord.web-app.dir=" + dir.resolve("web")).run(context -> {
            assertNull(context.getStartupFailure(), "a missing directory must not stop the backend");
            assertFalse(context.containsBean("webAppFallbackFilter"));
            assertFalse(context.containsBean("webAppHeadersFilter"), "no app, no headers of the app");
            assertTrue(context.getBeansOfType(WebAppConfig.class).isEmpty());
        });
    }
}
