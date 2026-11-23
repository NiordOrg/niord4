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
package org.niord.secom;

import org.grad.secomv2.core.models.enums.DigitalSignatureAlgorithmEnum;
import org.jboss.resteasy.plugins.server.servlet.HttpServlet30Dispatcher;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingValueCheck;
import org.niord.core.settings.SettingsService;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.pki.NiordSecomProviders;
import org.niord.secom.rest.MrnCertificateFilter;
import org.niord.secom.rest.SecomV2Application;
import org.niord.secom.service.SecomSubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Set;
import java.util.TreeSet;

/**
 * Wires the SECOM protocol adapter when {@code niord.secom.enabled=true} (default off — with
 * the flag off no servlet, filters, schedulers or SECOM beans exist at all):
 * <ul>
 *     <li>the SECOMLib provider implementations backed by the MCP keystore,</li>
 *     <li>a RESTEasy {@link HttpServlet30Dispatcher} mounted at {@code /api/secom/*} serving
 *         the {@link SecomV2Application} (mounted manually — the SECOMLib Boot starter
 *         targets Boot 3 and is not used; see docs/secom-design.md D1),</li>
 *     <li>a dedicated Spring Security chain for {@code /api/secom/**}: SECOM authentication
 *         is the {@link MrnCertificateFilter} (the peer's MCP certificate), never Keycloak JWT.</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class SecomConfiguration {

    /** The context path the SECOM servlet is mounted at */
    public static final String SECOM_BASE_PATH = "/api/secom";

    private static final Logger log = LoggerFactory.getLogger(SecomConfiguration.class);

    /*
     * DB-backed runtime settings (sysadmin Settings page), read through SettingsService where
     * they are used - each SECOM request, delivery and signature - so a save applies to the next
     * one. Read by name rather than by @Setting field injection: this configuration class is
     * instantiated at web-server init (for the SECOM servlet registration) before the settings
     * layer is usable.
     */

    static final Setting VALIDATE_CLIENT_CHAIN = new Setting("secomValidateClientChain", true,
            Setting.Type.Boolean, "Validate SECOM client certificate chains against the MCP truststore.",
            true, false, true);

    /**
     * Whether peer certificates are checked for revocation (OCSP, falling back to CRLs)
     * against the information embedded in the certificate, as the service design (§3.3.1)
     * requires — both inbound client certificates ({@link MrnCertificateFilter}) and the
     * server certificates of outbound SECOM connections ({@code SecomOutboundClient}).
     * Soft-fail: an unreachable OCSP responder/CRL does not reject the peer, only a
     * positive revocation does.
     */
    public static final Setting REVOCATION_CHECK = new Setting("secomRevocationCheck", true,
            Setting.Type.Boolean, "Check SECOM peer certificates for revocation, inbound and outbound (OCSP falling back to CRL;"
                    + " soft-fail).",
            true, false, true);

    /**
     * The SECOM payload/envelope signature algorithm used for outgoing signatures. Defaults
     * to the S-124 service design mandate (v0.995 §3.3.3), ECDSA-384-SHA2; set
     * {@code SHA3_384_WITH_ECDSA} to interoperate with legacy SHA3-only peers. Incoming
     * signatures are accepted in either 384-bit variant regardless of this setting.
     */
    static final Setting SIGNATURE_ALGORITHM = new Setting("secomSignatureAlgorithm", "SHA2_384_WITH_ECDSA",
            Setting.Type.String, "SECOM signature algorithm for outgoing signatures (SHA2_384_WITH_ECDSA or SHA3_384_WITH_ECDSA).",
            true, false, true);

    /** The names {@link #SIGNATURE_ALGORITHM} takes: the two algorithms outgoing signatures are made with */
    static final Set<String> SIGNATURE_ALGORITHMS = Set.of(DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA.name(),
            DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA.name());

    /**
     * Refuses a save of {@link #SIGNATURE_ALGORITHM} that names neither supported algorithm; a blank value keeps the
     * default. Where SECOM runs, which is where the setting has its row.
     */
    @Bean
    public SettingValueCheck secomSignatureAlgorithmCheck() {
        return (key, value) -> SIGNATURE_ALGORITHM.getKey().equals(key)
                && value instanceof String name && !name.isBlank() && !SIGNATURE_ALGORITHMS.contains(name.trim())
                ? key + " takes " + String.join(" or ", new TreeSet<>(SIGNATURE_ALGORITHMS)) + ", not " + name
                : null;
    }

    /** The last unsupported {@link #SIGNATURE_ALGORITHM} value logged, so a value is logged once, not per signature */
    private static volatile String loggedUnsupportedAlgorithm;

    /** The providers read {@link #SIGNATURE_ALGORITHM} at each signature ({@link #signatureAlgorithm}) */
    @Bean
    public NiordSecomProviders niordSecomProviders(McpSecurityService pki, SettingsService settingsService) {
        return new NiordSecomProviders(pki, () -> signatureAlgorithm(settingsService));
    }

    /**
     * Resolves {@link #SIGNATURE_ALGORITHM} for an outgoing signature. A blank value is the default. So is a value
     * that names neither supported algorithm, which a save refuses but a system property, an environment variable or
     * an older row can still hold: it is logged once, and signatures go on with the default instead of failing.
     *
     * @param settingsService the settings
     * @return the algorithm outgoing signatures are made with
     */
    static DigitalSignatureAlgorithmEnum signatureAlgorithm(SettingsService settingsService) {
        String defaultName = (String) SIGNATURE_ALGORITHM.getValue();
        Object value = settingsService.get(SIGNATURE_ALGORITHM);
        String name = value == null ? "" : value.toString().trim();
        if (name.isEmpty()) {
            return DigitalSignatureAlgorithmEnum.valueOf(defaultName);
        }
        if (!SIGNATURE_ALGORITHMS.contains(name)) {
            if (!name.equals(loggedUnsupportedAlgorithm)) {
                loggedUnsupportedAlgorithm = name;
                log.warn("{} is {}, which is neither {}: outgoing SECOM signatures use {}", SIGNATURE_ALGORITHM.getKey(),
                        name, String.join(" nor ", new TreeSet<>(SIGNATURE_ALGORITHMS)), defaultName);
            }
            return DigitalSignatureAlgorithmEnum.valueOf(defaultName);
        }
        return DigitalSignatureAlgorithmEnum.valueOf(name);
    }

    /**
     * Whether the {@code X-Secom-Cert} header is a trusted source of the peer identity. A
     * deployment property, not a runtime setting: it describes what sits in front of the app
     * (a TLS-terminating proxy that verifies the client certificate and sets the header, and
     * nothing else reaching the app). Off by default since SECOM Edition 2 - see
     * {@link MrnCertificateFilter}. The filter reads {@link #VALIDATE_CLIENT_CHAIN} and
     * {@link #REVOCATION_CHECK} at each request.
     */
    @Bean
    public MrnCertificateFilter mrnCertificateFilter(McpSecurityService pki, SecomSubscriptionService subscriptionService,
                                                     SettingsService settingsService,
                                                     @Value("${niord.secom.trust-cert-header:false}") boolean trustCertHeader) {
        return new MrnCertificateFilter(pki, subscriptionService, trustCertHeader,
                () -> booleanSetting(settingsService, VALIDATE_CLIENT_CHAIN),
                () -> booleanSetting(settingsService, REVOCATION_CHECK));
    }

    /**
     * Resolves a Boolean setting for the primitive filter and client checks, honoring system-property/env
     * overrides - which {@link SettingsService#get} surfaces as raw strings - read as
     * {@link SettingsService#getBoolean} reads them ({@link SettingsService#toBoolean}: the
     * true-ish and false-ish tokens). A value that is neither (missing, blank, a typo) is null
     * there and keeps the template default here, so it can never NPE on unboxing or silently
     * disable a security check.
     */
    public static boolean booleanSetting(SettingsService settingsService, Setting template) {
        Boolean value = SettingsService.toBoolean(settingsService.get(template));
        return value != null ? value : Boolean.TRUE.equals(template.getValue());
    }

    /**
     * Mounts the SECOM JAX-RS application on a RESTEasy dispatcher servlet.
     * <p>
     * Deliberately dependency-free and lazily initialized: ServletRegistrationBeans are
     * instantiated while Tomcat is still starting, before the JPA infrastructure exists —
     * so neither this bean nor servlet init may touch the resource's service graph.
     * {@link SecomV2Application} resolves the Spring beans on the first SECOM request.
     */
    @Bean
    public ServletRegistrationBean<HttpServlet30Dispatcher> secomServlet() {
        ServletRegistrationBean<HttpServlet30Dispatcher> registration =
                new ServletRegistrationBean<>(new HttpServlet30Dispatcher(), SECOM_BASE_PATH + "/*");
        registration.setName("secomServlet");
        registration.addInitParameter("jakarta.ws.rs.Application", SecomV2Application.class.getName());
        registration.addInitParameter("resteasy.servlet.mapping.prefix", SECOM_BASE_PATH);
        return registration;
    }

    /** Hands the fully started application context to the reflectively created JAX-RS application */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> secomContextHandover() {
        return event -> SecomV2Application.setApplicationContext(event.getApplicationContext());
    }

    /**
     * A dedicated security chain for the SECOM endpoints, matched before the application-wide
     * chain: permit-all at the Spring Security layer (no JWT processing, no CSRF - SECOM is
     * stateless server-to-server), authentication is the SECOM certificate filter's job.
     */
    @Bean
    @Order(50)
    public SecurityFilterChain secomSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(SECOM_BASE_PATH + "/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.disable());
        return http.build();
    }
}
