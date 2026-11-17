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
package org.niord.core.conf;

import org.niord.core.domain.DomainService;
import org.niord.core.web.NiordDomainFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring Security configuration — the Spring Boot 4 replacement for {@code quarkus-oidc}.
 *
 * <p>Replaces the Quarkus OIDC bearer-token pipeline with an OAuth2 resource server that
 * validates Keycloak JWTs and maps the roles of the OIDC base client
 * ({@code resource_access.<baseClient>.roles}) to Spring authorities. Keycloak <em>realm</em> roles
 * ({@code realm_access.roles}) are not mapped, as they were not in the Quarkus original: a realm role is
 * shared by every client of the realm, so one named like a Niord role would otherwise grant it everywhere.
 * Besides signature, expiry and issuer, a token must have been issued to one of the configured clients
 * ({@link #authorizedPartyValidator(String[])}). {@code @EnableMethodSecurity(jsr250Enabled = true)} keeps the
 * {@code @RolesAllowed} / {@code @PermitAll} annotations used throughout the REST layer enforcing —
 * <strong>without this flag they would silently become a fail-OPEN no-op</strong>
 * (see MIGRATION.md risk #2). {@code RestControllerSecurityAnnotationTest} asserts that every
 * handler method carries one of the annotations, so nothing is open merely by omission.
 *
 * <p>HTTP authorization is left permissive ({@code permitAll}); authorization is enforced at the
 * method level by JSR-250, matching the Quarkus model where {@code @PermitAll} resources are open
 * and {@code @RolesAllowed} resources require the role. A method-security denial surfaces as an
 * {@code AccessDeniedException} that propagates out of the {@code DispatcherServlet} to Spring
 * Security's {@code ExceptionTranslationFilter}, which answers with the resource-server defaults:
 * {@code 401} + {@code WWW-Authenticate: Bearer} for an anonymous caller
 * ({@code BearerTokenAuthenticationEntryPoint}) and {@code 403} for an authenticated caller lacking
 * the role ({@code BearerTokenAccessDeniedHandler}). The per-request {@code NiordDomain} domain
 * context and role augmentation is applied by {@link NiordDomainFilter}.
 *
 * <p>The HTTP layer cannot be tightened to {@code authenticated()}. The one-time <em>tickets</em>
 * that authorize non-Ajax downloads (see {@code TicketRestService}) travel as a {@code ?ticket=}
 * query parameter rather than as a bearer token, so Spring Security never sees them: every ticketed
 * export, and the {@code PublicationRestService} report self-call, would be rejected before its
 * ({@code @PermitAll}, programmatically role-checked) handler ran. The one exception is the
 * actuator. Those endpoints are not {@code @RestController} handlers, so neither a JSR-250
 * annotation nor {@code RestControllerSecurityAnnotationTest} covers them, and unlike every other
 * request-handling surface in the application their exposure widens by <em>configuration alone</em>
 * ({@code management.endpoints.web.exposure.include}) — no Java, hence nothing to review and nothing
 * to fail the build. They are therefore denied here, with the {@code health} endpoint — the k8s
 * liveness/readiness probe contract — permitted.
 *
 * <p>Under Boot's default exposure ({@code health} only) that pair changes exactly one response:
 * {@code EndpointRequest.toAnyEndpoint()} also covers the links index, so an anonymous
 * {@code GET /rest/actuator} — which used to answer {@code 200} with a discovery document listing the
 * exposed endpoints — is now {@code 401}. That is deliberate; the probe paths under
 * {@code /rest/actuator/health} are unaffected. Residual, and out of reach of any rule on this chain:
 * setting {@code management.server.port} moves the whole actuator surface onto a child management
 * context with its own filter chain, which this configuration does not apply to.
 */
@Configuration
@EnableMethodSecurity(jsr250Enabled = true)
public class SecurityConfig {

    /**
     * The OIDC base client whose roles form the caller's <em>base</em> identity, mirroring the original
     * {@code quarkus.oidc.client-id=niord-client-master} +
     * {@code quarkus.oidc.roles.role-claim-path=resource_access/niord-client-master/roles}. Roles for any
     * <em>other</em> client (e.g. {@code niord-client-nw}) must NOT be granted from the bare token — they
     * are only added per-request by the {@link NiordDomainFilter} for the domain the {@code NiordDomain}
     * header names. Granting every {@code resource_access.<client>.roles} unconditionally would fail OPEN
     * (e.g. leak the {@code user} role that lives only on {@code niord-client-nw}).
     */
    @Value("${niord.oidc.base-client:niord-client-master}")
    String baseClient;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, DomainService domainService) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // X-Frame-Options: DENY, except SAMEORIGIN on the web app's silent-SSO page (frameOptionsWriter).
            .headers(headers -> headers
                    .frameOptions(frame -> frame.disable())
                    .addHeaderWriter(frameOptionsWriter()))
            .authorizeHttpRequests(auth -> auth
                    // Deny-by-default for the actuator surface only - see the class javadoc for why
                    // the rest of the application cannot be tightened. Exposing a further endpoint
                    // (metrics, prometheus, ...) means permitting it here as well, deliberately.
                    .requestMatchers(EndpointRequest.to("health")).permitAll()
                    .requestMatchers(EndpointRequest.toAnyEndpoint()).denyAll()
                    .anyRequest().permitAll())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter())))
            // After the bearer token is authenticated, park the domain named by the NiordDomain header on
            // the request thread and add the roles the caller holds on that domain (the Spring replacement
            // for the Quarkus SecurityIdentityAugmentor).
            .addFilterAfter(new NiordDomainFilter(domainService),
                    BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** The administration web app's page for Keycloak's silent login check (fe/ui-apps/admin) */
    static final String SILENT_SSO_PAGE = "/silent-check-sso.html";

    /**
     * Writes {@code X-Frame-Options}: {@code DENY} on every response, as Spring Security's default does, except
     * {@code SAMEORIGIN} on {@value #SILENT_SSO_PAGE}. The administration web app, served from this origin
     * ({@link WebAppConfig}), loads that page in a hidden iframe for Keycloak's silent login check. The header
     * is sent on every response, but on the app's HTML documents browsers apply the Content-Security-Policy's
     * {@code frame-ancestors 'self'} ({@code WebAppHeadersFilter}) instead, so the app's own origin may frame
     * them, which is what the silent-SSO page needs. Every HTML the backend serves from user or country data is
     * sandboxed ({@code Content-Security-Policy: sandbox}: the repository's files, the report and mail
     * previews, {@code /assets/legal/} and {@code /assets/branding/}; a PDF is served from the repository
     * without it, for the browser's viewer), so none of it can abuse that; the app's own pages, Keycloak and a
     * country's build of the app are trusted.
     */
    static HeaderWriter frameOptionsWriter() {
        RequestMatcher silentSsoPage = PathPatternRequestMatcher.withDefaults().matcher(SILENT_SSO_PAGE);
        HeaderWriter sameOrigin = new XFrameOptionsHeaderWriter(XFrameOptionsMode.SAMEORIGIN);
        HeaderWriter deny = new XFrameOptionsHeaderWriter(XFrameOptionsMode.DENY);
        return (request, response) ->
                (silentSsoPage.matches(request) ? sameOrigin : deny).writeHeaders(request, response);
    }

    /**
     * Accepts a token only when its {@code azp} (authorized party) claim — the client the token was
     * issued to — is one of the configured clients. The resource server otherwise checks only signature,
     * expiry and issuer, so a token the realm issued to any of its clients (the Keycloak admin console,
     * a client added for another purpose) would be accepted.
     *
     * <p>{@code azp} rather than {@code aud}: Keycloak puts into {@code aud} only the clients the user
     * holds roles on, so an audience check on the base client would reject users whose roles are all on
     * a domain client, and only some realms add an audience mapper for the SPA client.
     *
     * <p>Spring Boot adds every {@code OAuth2TokenValidator<Jwt>} bean to the validators of the
     * {@code JwtDecoder} it builds, next to its own timestamp and issuer validators, so no decoder bean
     * is declared here. A token that fails the check is answered with {@code 401}.
     *
     * <p>The default admits the admin application's client ({@code niord-web}) and the base client.
     * A deployment that calls the API with a token from another client (a script, an end-to-end test
     * client) lists that client in {@code niord.oidc.allowed-clients} (comma-separated).
     */
    @Bean
    OAuth2TokenValidator<Jwt> authorizedPartyValidator(
            @Value("${niord.oidc.allowed-clients:niord-web,${niord.oidc.base-client:niord-client-master}}")
            String[] allowedClients) {
        return azpValidator(Arrays.asList(allowedClients));
    }

    /** The {@code azp} check of {@link #authorizedPartyValidator(String[])} for the given clients. */
    static OAuth2TokenValidator<Jwt> azpValidator(Collection<String> allowedClients) {
        Set<String> allowed = allowedClients.stream()
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new JwtClaimValidator<String>("azp", azp -> azp != null && allowed.contains(azp));
    }

    /**
     * Maps the <em>base client</em> roles ({@code resource_access.<baseClient>.roles}) in the JWT to
     * Spring authorities so that JSR-250 {@code @RolesAllowed(...)} resolves the same set the original
     * {@code role-claim-path} produced. Roles for other clients are intentionally NOT added here — see
     * {@link #baseClient}. Neither are Keycloak realm roles ({@code realm_access.roles}), which the
     * Quarkus original did not map either, nor OAuth scope authorities ({@code SCOPE_*}): nothing in the
     * application consumes them.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            // base-client roles ONLY (resource_access.<baseClient>.roles) — other clients are added
            // per-request by the NiordDomainFilter, never from the bare token.
            Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
            if (resourceAccess != null
                    && resourceAccess.get(baseClient) instanceof Map<?, ?> base
                    && base.get("roles") instanceof Collection<?> roles) {
                roles.forEach(r -> authorities.add(new SimpleGrantedAuthority(String.valueOf(r))));
            }
            return authorities;
        });
        return converter;
    }

    /**
     * The Niord roles ({@code sysadmin}, {@code admin}, {@code editor}, {@code user}) carry no
     * {@code ROLE_} prefix, and {@code @RolesAllowed("sysadmin")} must match the bare authority name
     * the {@link #jwtAuthConverter()} emits. JSR-250 method security otherwise prepends {@code ROLE_}
     * by default, which would never match — so disable the prefix.
     */
    @Bean
    static GrantedAuthorityDefaults grantedAuthorityDefaults() {
        return new GrantedAuthorityDefaults("");
    }
}
