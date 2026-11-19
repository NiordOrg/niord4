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
package org.niord.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.domain.DomainResolver;
import org.niord.core.domain.DomainService;
import org.niord.core.util.MdcContext;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Establishes the per-request Niord domain context from the domain (Keycloak client) the
 * {@link DomainResolver} extracts from the request — by default the {@code "NiordDomain"} header:
 * <ol>
 *     <li>Parks the domain ID on the request thread, so {@code DomainService.currentDomain()}
 *         resolves it for the duration of the request (the former {@code DomainServletFilter}).</li>
 *     <li>Adds the roles the authenticated bearer token carries on that domain — the Keycloak
 *         {@code resource_access.&lt;domain&gt;.roles} claim — to the caller's authentication (the
 *         former {@code NiordDomainSecurityIdentityAugmentor}, originally a Quarkus
 *         {@code SecurityIdentityAugmentor}). The base token only maps the OIDC base client's roles
 *         (see {@code SecurityConfig.jwtAuthConverter()}); this is the only source of a role a caller
 *         holds on a domain's client alone, e.g. the {@code user} role of a plain domain user.</li>
 * </ol>
 *
 * <p>Wired into the Spring Security filter chain after {@code BearerTokenAuthenticationFilter} by
 * {@code SecurityConfig}, so the JWT is already validated: the domain roles are read from the
 * <em>verified</em> {@link Jwt} claims — never by re-decoding the raw {@code Authorization} header.
 *
 * <p>The augmentation is purely additive and fails closed. A missing header, or a domain the token
 * holds no roles on, resolves to an empty role list. Roles the token does hold on the named client are
 * added only when that client is a stored, <em>active</em> Niord domain
 * ({@link DomainService#isActiveDomain(String)}): a token can carry roles on Keycloak clients that are
 * not domains (the SPA client itself, say), or on a domain that has since been deactivated or deleted
 * while its Keycloak client and role mappings remain, and none of those may grant anything. The lookup
 * runs only when the token holds roles on the named client, so a request without the header, or an
 * anonymous one, costs no query. An unauthenticated caller has no {@link JwtAuthenticationToken} and is
 * never augmented.
 */
public class NiordDomainFilter extends OncePerRequestFilter {

    private final DomainService domainService;

    private final DomainResolver domainResolver = DomainResolver.newInstance();

    /**
     * The shape of a Keycloak client id, which is what a domain id is — the same key the JWT
     * {@code resource_access} claim is indexed by.
     *
     * <p>Applied only on the way into the {@link MDC}, and only because {@link DomainResolver}
     * returns the {@code NiordDomain} request header <em>verbatim</em>: copying an unvalidated
     * header into every log line would reintroduce exactly the log injection
     * {@link RequestIdFilter} exists to prevent.
     *
     * <p>The check deliberately lives here rather than in {@link DomainResolver}. The resolver's
     * output also feeds {@link #augmentAuthentication(String)}, whose fail-closed role lookup has
     * its own contract and test matrix; tightening domain <em>resolution</em> in order to fix a
     * <em>logging</em> problem would change a security-relevant path for no reason. An unsafe
     * domain is parked on the thread and goes through the same role augmentation as any other
     * header value (which adds nothing unless it names an active domain) — it is only kept out of
     * the log.
     */
    private static final Pattern SAFE_DOMAIN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public NiordDomainFilter(DomainService domainService) {
        this.domainService = domainService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String domainId = domainResolver.resolveDomain(request);
        if (StringUtils.isNotBlank(domainId)) {
            domainService.setDomainForCurrentThread(domainId);
            augmentAuthentication(domainId);
            if (SAFE_DOMAIN.matcher(domainId).matches()) {
                // Omitted rather than replaced when it fails the check: unlike a request id there is
                // nothing meaningful to substitute, and an empty dom= already reads as "no domain".
                MDC.put(MdcContext.DOMAIN, domainId);
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MdcContext.DOMAIN);
            domainService.removeDomainForCurrentThread();
        }
    }

    /**
     * Re-decorates the current {@link JwtAuthenticationToken} with the union of its authorities and
     * the roles its (verified) token carries on the given domain, if that adds anything and the
     * domain is a stored, active Niord domain.
     */
    private void augmentAuthentication(String domainId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && jwtAuth.isAuthenticated()) {
            List<String> domainRoles = domainRoles(jwtAuth.getToken(), domainId);
            if (!domainRoles.isEmpty() && domainService.isActiveDomain(domainId)) {
                Set<GrantedAuthority> merged = new LinkedHashSet<>(jwtAuth.getAuthorities());
                domainRoles.forEach(r -> merged.add(new SimpleGrantedAuthority(r)));
                if (merged.size() != jwtAuth.getAuthorities().size()) {
                    JwtAuthenticationToken augmented =
                            new JwtAuthenticationToken(jwtAuth.getToken(), merged, jwtAuth.getName());
                    augmented.setDetails(jwtAuth.getDetails());
                    SecurityContextHolder.getContext().setAuthentication(augmented);
                }
            }
        }
    }

    /**
     * Returns the roles of the {@code resource_access.<domain>.roles} claim of the given verified
     * token, or an empty list whenever any link in that chain is missing (fail closed).
     */
    static List<String> domainRoles(Jwt token, String domain) {
        if (token.getClaims().get("resource_access") instanceof Map<?, ?> resourceAccess
                && resourceAccess.get(domain) instanceof Map<?, ?> domainAccess
                && domainAccess.get("roles") instanceof List<?> roles) {
            return roles.stream().map(Object::toString).toList();
        }
        return List.of();
    }
}
