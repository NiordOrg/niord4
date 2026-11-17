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
package org.niord.test;

import org.niord.test.http.HttpStub;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * Spring replacement for the Quarkus {@code WmsProviderStubResource}
 * ({@code QuarkusTestResourceLifecycleManager}) that pointed {@code WmsProxyServlet}'s injected
 * {@code wmsProvider} at a local {@link HttpStub} for the lifetime of one test class.
 *
 * <p><b>Why system properties (not {@code @DynamicPropertySource}).</b> Niord resolves
 * {@code @Setting}-injected values through {@code SettingsService.get(...)}, which checks
 * {@link System#getProperty(String)} <em>before</em> the cache/DB (and before any declared default) —
 * see {@code SettingsService.get(Setting)}. It does <em>not</em> read the Spring {@code Environment}, so
 * a {@code @DynamicPropertySource} binding would never reach the servlet. Setting the JVM system
 * properties here — the same mechanism the Quarkus fixture used — therefore makes the servlet's
 * {@code wmsProvider}, {@code wmsPassword} and {@code wmsLayers} resolve to our values when the bean's
 * {@code @Setting} fields are populated:
 *
 * <ul>
 *   <li>{@code wmsProvider} -> the stub's base URL (the proxy's upstream endpoint),</li>
 *   <li>{@code wmsPassword} -> {@code tok-123} (appended by the proxy as {@code &TOKEN=...}),</li>
 *   <li>{@code wmsLayers}   -> {@code layerA} (appended by the proxy as {@code &LAYERS=...}).</li>
 * </ul>
 *
 * <p><b>Why the delegate reset.</b> In the Spring port {@code WmsProxyServlet} is not registered
 * directly; {@code WmsServletConfig.DelegatingWmsServlet} lazily creates the real servlet on its
 * <em>first</em> {@code /wms/*} request and caches it in a {@code volatile} field — at which point its
 * {@code @Setting} fields are resolved <em>once</em> by the {@code SettingInjectionBeanPostProcessor}.
 * The {@code @SpringBootTest} context (and therefore that cached delegate) is shared with the
 * sibling {@code WmsProxyServletITTest}, so whichever class hits {@code /wms/*} first would otherwise
 * pin the provider for both. To stay order-independent we null out the cached delegate via reflection
 * when the stub starts (so the next request re-wires the servlet against our system properties) and
 * again when it stops (so the no-provider blank-image fallback suite gets a clean, provider-less
 * delegate regardless of which class ran first).
 *
 * <p>Usage: {@link #start(ApplicationContext)} in {@code @BeforeAll}, {@link #close()} in
 * {@code @AfterAll}. Like the Quarkus {@code restrictToAnnotatedClass = true}, the lifecycle is scoped
 * to the single test class, and {@link #close()} clears the three properties again so the shared
 * application the rest of the integration tests rely on is never polluted.
 *
 * <p>This is a framework-coupled test fixture — see docs/framework-coupled-tests.md.
 */
public final class WmsProviderStub implements AutoCloseable {

    static final String PROVIDER_KEY = "wmsProvider";
    static final String PASSWORD_KEY = "wmsPassword";
    static final String LAYERS_KEY   = "wmsLayers";

    /** Bean name of the {@code ServletRegistrationBean} declared in {@code WmsServletConfig}. */
    private static final String REGISTRATION_BEAN = "wmsProxyServletRegistration";

    private final ApplicationContext context;
    private final HttpStub stub;

    private WmsProviderStub(ApplicationContext context, HttpStub stub) {
        this.context = context;
        this.stub = stub;
    }

    /**
     * Start the stub, publish the {@code wmsProvider}/{@code wmsPassword}/{@code wmsLayers} system
     * properties, and clear the lazily-cached {@code DelegatingWmsServlet} delegate so the servlet is
     * re-wired against these properties on the next request.
     */
    public static WmsProviderStub start(ApplicationContext context) {
        HttpStub stub;
        try {
            stub = HttpStub.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start WMS HttpStub", e);
        }
        stub.respondBytes("image/png", pngBytes());

        System.setProperty(PROVIDER_KEY, stub.baseUrl());
        System.setProperty(PASSWORD_KEY, "tok-123");
        System.setProperty(LAYERS_KEY, "layerA");

        WmsProviderStub providerStub = new WmsProviderStub(context, stub);
        providerStub.resetServletDelegate();
        return providerStub;
    }

    /** The running stub, so the test can assert against the upstream request the proxy made. */
    public HttpStub stub() {
        return stub;
    }

    @Override
    public void close() {
        System.clearProperty(PROVIDER_KEY);
        System.clearProperty(PASSWORD_KEY);
        System.clearProperty(LAYERS_KEY);
        // Re-wire on the next request without our provider, so the shared no-provider suite is clean.
        resetServletDelegate();
        stub.close();
    }

    /**
     * Null out {@code WmsServletConfig.DelegatingWmsServlet.delegate} so the next {@code /wms/*}
     * request re-creates and re-wires the {@code WmsProxyServlet} (re-resolving its {@code @Setting}
     * fields against the currently-set system properties). Reflective because the delegating servlet
     * is a package-private static class in the application module.
     */
    private void resetServletDelegate() {
        ServletRegistrationBean<?> registration =
                context.getBean(REGISTRATION_BEAN, ServletRegistrationBean.class);
        Object servlet = registration.getServlet();
        if (servlet == null) {
            return;
        }
        Field delegateField = ReflectionUtils.findField(servlet.getClass(), "delegate");
        if (delegateField == null) {
            throw new IllegalStateException(
                    "Expected a 'delegate' field on " + servlet.getClass().getName()
                            + " (WmsServletConfig.DelegatingWmsServlet) to reset the cached WmsProxyServlet");
        }
        ReflectionUtils.makeAccessible(delegateField);
        ReflectionUtils.setField(delegateField, servlet, null);
    }

    /** A minimal, decodable 16x16 PNG used as the upstream WMS tile. */
    private static byte[] pngBytes() {
        try {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate stub PNG", e);
        }
    }
}
