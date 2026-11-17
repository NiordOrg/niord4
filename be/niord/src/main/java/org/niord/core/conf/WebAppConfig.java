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

import org.niord.core.keycloak.KeycloakIntegrationService;
import org.niord.core.settings.SettingsService;
import org.niord.web.WebAppContentSecurityPolicy;
import org.niord.web.WebAppFallbackFilter;
import org.niord.web.WebAppHeadersFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.CacheControl;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Serves the administration web app from the directory {@value #DIR_PROPERTY} names, at the root of the
 * backend's origin. A deployment serves the app, the API ({@code /rest}) and Keycloak ({@code /auth}) from
 * that one origin, so the app's production build names no host. Each deployable image carries its country's
 * build of {@code fe/ui-apps/admin} and sets the property ({@code k8s/docker/Dockerfile}). Nothing here is
 * registered unless the directory holds the build's {@code index.html}: without the property, and in an image
 * built without the web app ({@code build-image.sh --no-web}), the root answers 404 and no navigation is
 * forwarded.
 *
 * <p>Three parts:
 * <ul>
 *   <li><b>The files.</b> The build's top-level files ({@code index.html}, the bundles, the service worker,
 *   the silent-SSO page) and its {@code assets/} and {@code media/} folders are served from the directory.
 *   The content-hashed files are cached for a year as immutable, and everything else is revalidated on
 *   every use ({@code no-cache}), so a new release is picked up at once. The server-side assets under
 *   {@code /img} and {@code /css/templates} keep Spring Boot's default handler: its {@code /**} mapping is
 *   less specific than every pattern here.</li>
 *   <li><b>The app's own addresses.</b> {@link WebAppFallbackFilter} answers a browser's navigation to
 *   {@code /messages} or {@code /editor/...} with {@code index.html}. It runs after the Spring Security
 *   filter chain ({@value #SPRING_SECURITY_FILTER_ORDER}), so the forwarded page carries the same security
 *   headers as every other response.</li>
 *   <li><b>The headers.</b> {@link WebAppHeadersFilter} sends the app's HTML documents (every navigation,
 *   {@code index.html}, {@code silent-check-sso.html}) with the Content-Security-Policy of
 *   {@link WebAppContentSecurityPolicy}, built from the current tile settings and the token issuer, and the
 *   files under {@code assets/legal/} and {@code assets/branding/}, which a country supplies, with
 *   {@code Content-Security-Policy: sandbox} and {@code nosniff}. It runs after the Spring Security filter
 *   chain and before the fallback. The policy is not a bean: this class constructs it, and no country can
 *   replace this class or its bean ({@code CountryContractGuard}). The guard does not check servlet filters:
 *   a country filter that rewrites or drops the header is refused by review
 *   ({@code docs/fe-country-split-plan.md} §4.11 rule 3).</li>
 * </ul>
 *
 * <p>The admin-app redirect ({@link AdminAppRedirectWebConfig}) runs first: a deployment whose
 * {@code adminAppUrl} names another origin still sends its root there.
 */
@Configuration
@Conditional(WebAppConfig.AppBuilt.class)
public class WebAppConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebAppConfig.class);

    /** The property naming the directory of the web app's build */
    public static final String DIR_PROPERTY = "niord.web-app.dir";

    /** The property naming the token issuer, the Keycloak realm the browser signs in to */
    static final String ISSUER_URI_PROPERTY = KeycloakIntegrationService.ISSUER_URI_PROPERTY;

    /**
     * The order Spring Boot registers {@code springSecurityFilterChain} at
     * ({@code SecurityFilterProperties.DEFAULT_FILTER_ORDER}), written out as in {@link TicketWebConfig}.
     */
    static final int SPRING_SECURITY_FILTER_ORDER = -100;

    /** The content-hashed top-level files of an Angular production build ({@code outputHashing: all}) */
    static final String[] HASHED_FILES = {"/main-*.js", "/chunk-*.js", "/polyfills-*.js", "/styles-*.css"};

    /** Every other top-level file of the build */
    static final String OTHER_FILES = "/*.*";

    private final Path dir;

    WebAppConfig(@Value("${" + DIR_PROPERTY + "}") String dir) {
        this.dir = resolve(dir);
        log.info("Serving the web app from {}", this.dir);
    }

    /** Returns the web app's directory from the value of {@value #DIR_PROPERTY} */
    static Path resolve(String dir) {
        return Paths.get(dir.trim()).toAbsolutePath().normalize();
    }

    /**
     * {@inheritDoc}
     * <p>
     * A folder pattern resolves only the part {@code /**} matched, so each folder is its own location.
     * {@code media/} holds the fonts and images the build hashed; {@code assets/} is copied unhashed.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl immutable = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
        registry.addResourceHandler(HASHED_FILES).addResourceLocations(location("")).setCacheControl(immutable);
        registry.addResourceHandler("/media/**").addResourceLocations(location("media")).setCacheControl(immutable);
        registry.addResourceHandler(OTHER_FILES).addResourceLocations(location("")).setCacheControl(CacheControl.noCache());
        registry.addResourceHandler("/assets/**").addResourceLocations(location("assets"))
                .setCacheControl(CacheControl.noCache());
    }

    /** Returns the resource location of a folder of the web app, ending in a slash as Spring requires */
    private String location(String folder) {
        return StringUtils.trimTrailingCharacter(dir.resolve(folder).toUri().toString(), '/') + "/";
    }

    /**
     * Registers the headers filter.
     * <p>
     * The settings service comes as a provider, resolved on the first HTML request: a filter registration is
     * created with the embedded web server, before the persistence layer the service needs exists.
     */
    @Bean
    public FilterRegistrationBean<WebAppHeadersFilter> webAppHeadersFilter(
            ObjectProvider<SettingsService> settingsService, Environment environment) {
        WebAppContentSecurityPolicy policy = new WebAppContentSecurityPolicy(settingsService::getObject,
                environment.getProperty(ISSUER_URI_PROPERTY));
        FilterRegistrationBean<WebAppHeadersFilter> reg = new FilterRegistrationBean<>(new WebAppHeadersFilter(policy));
        reg.addUrlPatterns("/*");
        reg.setName(WebAppHeadersFilter.class.getSimpleName());
        reg.setOrder(SPRING_SECURITY_FILTER_ORDER + 5);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<WebAppFallbackFilter> webAppFallbackFilter() {
        FilterRegistrationBean<WebAppFallbackFilter> reg = new FilterRegistrationBean<>(new WebAppFallbackFilter());
        reg.addUrlPatterns("/*");
        reg.setName(WebAppFallbackFilter.class.getSimpleName());
        reg.setOrder(SPRING_SECURITY_FILTER_ORDER + 10);
        return reg;
    }

    /**
     * Matches when {@value #DIR_PROPERTY} names a directory that holds the build's {@code index.html}.
     * <p>
     * The property alone is not enough: the Dockerfile sets it in every image, also in one built without the
     * web app. Registering the fallback filter there would forward every navigation to a page that does not
     * exist. A set property that names no build is logged as a warning, once at start: an image meant to
     * carry the app, whose copy of it went wrong, then says so. A value that is no valid path is treated the
     * same way, so it cannot stop the backend from starting.
     */
    static class AppBuilt implements Condition {
        /** {@inheritDoc} */
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment().getProperty(DIR_PROPERTY);
            if (!StringUtils.hasText(value)) {
                return false;
            }
            Path dir;
            try {
                dir = resolve(value);
            } catch (InvalidPathException e) {
                log.warn("{} is '{}', which is not a valid path: the web app is not served", DIR_PROPERTY, value);
                return false;
            }
            if (Files.isRegularFile(dir.resolve("index.html"))) {
                return true;
            }
            log.warn("{} is {}, which holds no index.html: the web app is not served", DIR_PROPERTY, dir);
            return false;
        }
    }
}
