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
package org.niord.web;

import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The companion to {@link RestControllerSecurityAnnotationTest}, covering everything that guard
 * cannot see.
 *
 * <p>Authorization is JSR-250 method security on {@code @Controller} handlers, and the HTTP layer
 * is {@code permitAll} (see {@code SecurityConfig}). A servlet, a servlet filter or a JAX-RS resource
 * carries no {@code @RolesAllowed}, is never proxied by method security, and is therefore anonymous
 * unless it authorizes the caller itself. The sibling guards scan {@code @Controller}s
 * ({@link RestControllerSecurityAnnotationTest}) and the handlers registered with Spring MVC
 * ({@code RestHandlerSecurityITTest}), and so cannot say a word about any of them.
 *
 * <p>This test does not — and cannot — decide whether such a surface is authorized correctly. It pins
 * the <em>inventory</em>: every non-controller class in {@code org.niord} that can see an HTTP request
 * is listed below with the mechanism that authorizes it. Adding a surface is then a deliberate act —
 * the build fails until the author records how the new surface is authorized, or makes it fail closed
 * first. That is the runtime hole {@code permitAll} leaves, turned into a build-time decision point.
 *
 * <p>The scan is condition-blind for the same reason as the sibling guard: a plain
 * {@code ClassPathScanningCandidateComponentProvider} would drop {@code SecomV2Resource}, whose
 * {@code @ConditionalOnProperty} does not match the test environment.
 *
 * <p>What this guard does NOT cover, so that its silence is not mistaken for coverage:
 * <ul>
 *   <li>a <em>third-party</em> class mounted by Niord code — the scan is scoped to {@code org.niord},
 *       so {@code SecomConfiguration}'s {@code ServletRegistrationBean<HttpServlet30Dispatcher>} at
 *       {@code /api/secom/*} is invisible to it (that surface is authorized by
 *       {@code MrnCertificateFilter}, and its own chain is {@code SecomConfiguration}'s);</li>
 *   <li>the sibling deployables — this test runs on {@code niord}'s test classpath, and
 *       {@code niord-app}/{@code niord-dk} are not on it;</li>
 *   <li>whether a listed surface's own check is <em>correct</em>. {@code WmsProxyServlet}'s
 *       {@code wmsProtected} is a sysadmin-editable setting, so {@code /wms/*} can be opened to
 *       anonymous callers at runtime with no code change and no test failure.</li>
 * </ul>
 *
 * <p>Test classes are excluded by the {@code *Test} naming convention, which cuts both ways: a test
 * helper implementing {@code Filter}/{@code Servlet} under {@code org.niord} but NOT named
 * {@code *Test} reds this build with a security-flavoured message it does not deserve. Add it to the
 * list with a comment saying it is a test double.
 */
public class NonControllerHttpSurfaceInventoryTest {

    /**
     * Every non-{@code @Controller} class in {@code org.niord} that can see an HTTP request, with
     * the mechanism that authorizes it. Keep the comments accurate — they are the whole point of the
     * list.
     */
    private static final Set<String> REVIEWED_SURFACES = new TreeSet<>(List.of(

            // --- Servlets ---------------------------------------------------------------------- //

            // A dependency-free lazy delegate to WmsProxyServlet (WmsServletConfig); the
            // authorization is the delegate's, this class handles nothing itself.
            "org.niord.core.conf.WmsServletConfig$DelegatingWmsServlet",

            // Mapped at /wms/* in EVERY profile (WmsServletConfig). Self-guarding: the "wmsProtected"
            // setting - default true - answers 403 to a caller with no principal.
            "org.niord.web.wms.WmsProxyServlet",

            // --- JAX-RS resources -------------------------------------------------------------- //

            // Only when niord.secom.enabled=true. Authenticated by MrnCertificateFilter, a global
            // @Provider on the SECOM JAX-RS application (SecomV2Application), which rejects any
            // request without a valid MCP peer certificate. The single exception is GET /v2/ping.
            "org.niord.secom.rest.SecomV2Resource",

            // --- Servlet filters ---------------------------------------------------------------- //
            // None of these authorize anything; they are listed so that one that DOES cannot appear
            // here unnoticed.

            // Instantiates and delegates to another filter in this list (AdminAppRedirectWebConfig,
            // TicketWebConfig).
            "org.niord.core.conf.LazyFilter",

            // Swallows "Broken pipe" IOExceptions. Not registered with the servlet container at all
            // in the Spring port.
            "org.niord.core.repo.RepositoryServletFilter",

            // Part of the Spring Security chain (SecurityConfig): parks the NiordDomain and adds the
            // caller's roles on that domain when it is an existing, active Niord domain. Reads VERIFIED
            // Jwt claims only; never serves a response.
            "org.niord.core.web.NiordDomainFilter",

            // Correlation ids (CorrelationConfig); never serves a response.
            "org.niord.core.web.RequestIdFilter",

            // Registered in EVERY profile (AdminAppRedirectWebConfig). Answers a GET/HEAD for "/" with a
            // 302 to the adminAppUrl setting; serves no content and grants nothing.
            "org.niord.web.AdminAppRedirectFilter",

            // Registered only when niord.web-app.dir names a directory that holds the web app's
            // index.html (WebAppConfig). Forwards a browser's GET/HEAD
            // navigation to a web-app address (no file extension, outside /rest, /api, /wms, /auth, /img,
            // /css, /assets, /media), and any GET/HEAD for "/" whatever it accepts, to /index.html, the
            // app's public shell. Serves no data and grants
            // nothing; the app reaches data only through /rest, where method security applies.
            "org.niord.web.WebAppFallbackFilter",

            // Registered only when the web app is served (WebAppConfig). Sets the Content-Security-Policy
            // on the app's HTML documents and sandbox + nosniff on /assets/legal/ and /assets/branding/.
            // Writes response headers only; serves nothing and grants nothing.
            "org.niord.web.WebAppHeadersFilter",

            // Registered in EVERY profile (TicketWebConfig). Resolves the ?ticket= parameter onto the
            // request thread. Grants nothing by itself - UserService/DomainService decide what the
            // ticket data is worth: a role-less ticket grants no role (TICKET1), and for a caller whose
            // only credential is the ticket, the ticket's domain is the request's domain.
            "org.niord.web.TicketServletFilter"));

    @Test
    public void everyNonControllerHttpSurfaceIsOnTheReviewedInventory() {

        TypeFilter[] surfaces = {
                new AssignableTypeFilter(Servlet.class),
                new AssignableTypeFilter(Filter.class),
                new AnnotationTypeFilter(Path.class)
        };
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    /** The include filters alone, without the condition evaluation super applies */
                    @Override
                    protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
                        for (TypeFilter surface : surfaces) {
                            if (surface.match(metadataReader, getMetadataReaderFactory())) {
                                return true;
                            }
                        }
                        return false;
                    }
                };
        for (TypeFilter surface : surfaces) {
            scanner.addIncludeFilter(surface);
        }

        Set<String> found = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("org.niord")) {
            String className = candidate.getBeanClassName();
            // The scan sees this module's own test classes too; a test double is not a deployed surface
            if (!topLevelName(className).endsWith("Test")) {
                found.add(className);
            }
        }

        // Guard the guard: an empty or partial classpath scan must not pass vacuously
        assertTrue(found.contains("org.niord.web.wms.WmsProxyServlet"),
                "sanity: the classpath scan must find the servlet layer, found: " + found);

        assertEquals(REVIEWED_SURFACES, found,
                "A servlet, servlet filter or JAX-RS resource carries no JSR-250 annotation and is not "
                        + "reached by method security, so under permitAll-at-HTTP-layer it is ANONYMOUS "
                        + "unless it authorizes the caller itself. Give the new surface an explicit "
                        + "authorization decision, then add it to REVIEWED_SURFACES with a comment "
                        + "naming that decision — or remove the entry if the surface is gone");
    }

    /** The outer class name of a possibly nested class name */
    private static String topLevelName(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }
}
