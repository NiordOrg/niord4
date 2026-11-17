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
package org.niord.core.keycloak;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Framework-neutral unit tests for the URL-resolution logic of {@link KeycloakIntegrationService}.
 *
 * <p>These tests deliberately use no Quarkus and no container: the service is instantiated with
 * {@code new KeycloakIntegrationService()} and its {@code @Inject} fields are populated via reflection.
 * The collaborator that supplies the base URI ({@link NiordApp}) is a Mockito mock rather than a CDI
 * bean, so the test survives a future Spring migration unchanged.
 *
 * <p>The area under test:
 * <ul>
 *   <li>{@code resolveAuthServerUrl} (private) — exercised via reflection: the blank-url failure, and
 *       the exact slash-joining of a relative auth-server URL onto the configured base URI, which is the
 *       only base it is resolved against.</li>
 *   <li>{@code trustAllCertificates} — TLS is verified unless the setting explicitly says otherwise.</li>
 * </ul>
 */
public class KeycloakDeploymentTest {

    // ------------------------------------------------------------------ //
    // Reflection helpers
    // ------------------------------------------------------------------ //

    /** Reflection field-setter that walks the class hierarchy. */
    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }

    /** Invokes a no-arg private method by name. */
    private static Object invoke(Object target, String method) throws Throwable {
        Method m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        try {
            return m.invoke(target);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** A service with a mocked application and a logger; callers set the URL field they need. */
    private static KeycloakIntegrationService newService(NiordApp app) throws Exception {
        KeycloakIntegrationService svc = new KeycloakIntegrationService();
        set(svc, "log", LoggerFactory.getLogger("kc-deploy-test"));
        set(svc, "app", app);
        return svc;
    }

    // ================================================================== //
    // 2) resolveAuthServerUrl - blank url throws
    // ================================================================== //

    @Test
    public void resolveAuthServerUrl_blankUrl_throwsRuntimeException() throws Throwable {
        KeycloakIntegrationService svc = newService(mock(NiordApp.class));
        set(svc, "authServerUrl", "   ");   // blank (whitespace) -> StringUtils.isBlank == true

        try {
            invoke(svc, "resolveAuthServerUrl");
            fail("a blank authServerUrl must be rejected");
        } catch (RuntimeException e) {
            assertEquals("No authServerUrl setting defined", e.getMessage());
        }
    }

    // ================================================================== //
    // 3) resolveAuthServerUrl - relative url joined with exactly one slash
    // ================================================================== //

    @Test
    public void resolveAuthServerUrl_relativeUrl_joinedWithExactlyOneSlash() throws Throwable {
        NiordApp app = mock(NiordApp.class);
        // Base URI has NO trailing slash, relative url has NO leading slash -> one slash must be inserted.
        when(app.getBaseUri()).thenReturn("https://niord.example.com");

        KeycloakIntegrationService svc = newService(app);
        set(svc, "authServerUrl", "auth");

        String resolved = (String) invoke(svc, "resolveAuthServerUrl");

        assertEquals("https://niord.example.com/auth", resolved,
                "relative url must be joined to the base uri with a single slash");
    }

    // ================================================================== //
    // 4) resolveAuthServerUrl - leading-slash url does not double the slash
    // ================================================================== //

    @Test
    public void resolveAuthServerUrl_leadingSlashUrl_withTrailingSlashBase_noDoubledSlash() throws Throwable {
        NiordApp app = mock(NiordApp.class);
        // Base URI HAS a trailing slash, relative url HAS a leading slash -> the duplicate must be removed.
        when(app.getBaseUri()).thenReturn("https://niord.example.com/");

        KeycloakIntegrationService svc = newService(app);
        set(svc, "authServerUrl", "/auth");

        String resolved = (String) invoke(svc, "resolveAuthServerUrl");

        assertEquals("https://niord.example.com/auth", resolved,
                "a leading-slash url on a trailing-slash base must not produce '//'");
        assertFalse(resolved.substring("https://".length()).contains("//"),
                "there must be no doubled slash in the resolved authority+path");
    }

    @Test
    public void resolveAuthServerUrl_relativeUrl_resolvesAgainstTheConfiguredBaseUriOnly() throws Throwable {
        // The admin client sends the caller's bearer token to this URL, so a relative url is resolved
        // against the configured baseUri alone: the Host / X-Forwarded-Host of the request plays no part.
        NiordApp app = mock(NiordApp.class);
        when(app.getBaseUri()).thenReturn("https://niord.example.com");
        KeycloakIntegrationService svc = newService(app);
        set(svc, "authServerUrl", "/auth");

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("attacker.example.net");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("Host")).thenReturn("attacker.example.net:8080");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("attacker.example.net");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertEquals("https://niord.example.com/auth", invoke(svc, "resolveAuthServerUrl"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        verify(app).getBaseUri();
        verifyNoMoreInteractions(app);
    }

    @Test
    public void tlsIsVerifiedUnlessTheSettingExplicitlyTurnsItOff() throws Exception {
        KeycloakIntegrationService svc = newService(mock(NiordApp.class));

        set(svc, "authServerTrustAllCertificates", null);
        assertFalse(svc.trustAllCertificates(), "an unset setting verifies TLS");
        set(svc, "authServerTrustAllCertificates", Boolean.FALSE);
        assertFalse(svc.trustAllCertificates(), "the default verifies TLS");
        set(svc, "authServerTrustAllCertificates", Boolean.TRUE);
        assertTrue(svc.trustAllCertificates(), "only an explicit true skips verification");
    }

    @Test
    public void resolveAuthServerUrl_absoluteUrl_isReturnedUnchanged() throws Throwable {
        // An absolute http(s) url must bypass base-uri joining entirely (app is never consulted).
        NiordApp app = mock(NiordApp.class);
        KeycloakIntegrationService svc = newService(app);
        set(svc, "authServerUrl", "https://kc.example.com/auth");

        String resolved = (String) invoke(svc, "resolveAuthServerUrl");

        assertEquals("https://kc.example.com/auth", resolved,
                "an absolute url must be returned verbatim");
        verifyNoInteractions(app);
    }
}
