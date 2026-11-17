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
package org.example.country;

import org.niord.core.domain.DomainService;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * A country's factory methods for the beans the guard protects, as the bean definitions of
 * {@code CountryContractGuardTest} name them. Not a configuration: the tests register the definitions by hand and
 * never call a method.
 */
public class CountryBeans {

    /** niord-base's class, made by a country's factory method: a replacement all the same */
    public DomainService domainService() {
        return new DomainService();
    }

    public SecurityFilterChain securityFilterChain() {
        return new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE);
    }

    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        return new JwtAuthenticationConverter();
    }

    public GrantedAuthorityDefaults grantedAuthorityDefaults() {
        return new GrantedAuthorityDefaults("ROLE_");
    }

    public JwtDecoder jwtDecoder() {
        return token -> {
            throw new BadJwtException("a fixture decoder");
        };
    }

    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("user > sysadmin");
    }

    public CorsConfigurationSource corsConfigurationSource() {
        return new UrlBasedCorsConfigurationSource();
    }

    /** A token validator only narrows what is accepted: never refused */
    public OAuth2TokenValidator<Jwt> tokenValidator() {
        return jwt -> OAuth2TokenValidatorResult.success();
    }
}
