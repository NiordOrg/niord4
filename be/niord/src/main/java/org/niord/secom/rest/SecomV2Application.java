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
package org.niord.secom.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.ws.rs.core.Application;
import org.grad.secomv2.core.components.ContainerTypeConverterProvider;
import org.grad.secomv2.core.components.DigitalSignatureAlgorithmConverterProvider;
import org.grad.secomv2.core.components.InstantToISOConverterProvider;
import org.grad.secomv2.core.components.SecomObjectMapperProvider;
import org.grad.secomv2.core.components.SecomReaderInterceptor;
import org.grad.secomv2.core.components.SecomSignatureFilter;
import org.grad.secomv2.core.components.SecomV2ExceptionMapper;
import org.grad.secomv2.core.components.SecomWriterInterceptor;
import org.niord.secom.pki.NiordSecomProviders;
import org.springframework.context.ApplicationContext;

import java.util.Set;

/**
 * The JAX-RS application served by the RESTEasy servlet at {@code /api/secom}: the
 * {@link SecomV2Resource} plus the SECOMLib protocol machinery — writer interceptor
 * (exchange metadata + payload signing + base64 encoding), signature filter (validates
 * signed POST bodies such as acknowledgements), reader interceptor, exception mapper
 * (SECOM error shapes) and the Instant/enum query-parameter converters.
 * <p>
 * Mirrors SECOMLib's own {@code SecomV2JaxrsApplication} minus its Boot-3-only
 * autoconfiguration, OpenAPI resources and CORS filter (SECOM is server-to-server).
 * The SECOM wire JSON deliberately uses a dedicated Jackson 2 mapper — the SECOMLib
 * models carry Jackson 2 annotations and never touch Niord's Jackson 3 MVC converters.
 * <p>
 * Instantiated reflectively by RESTEasy on the servlet's first request (the servlet is
 * deliberately NOT load-on-startup: pulling the resource's service graph while Tomcat is
 * still starting would initialize JPA-dependent beans before the persistence
 * infrastructure exists). The Spring context is handed over statically by
 * {@code SecomConfiguration}; beans are resolved lazily here.
 */
public class SecomV2Application extends Application {

    /** Set by SecomConfiguration when the SECOM integration is enabled */
    private static volatile ApplicationContext applicationContext;

    public static void setApplicationContext(ApplicationContext context) {
        applicationContext = context;
    }

    /** {@inheritDoc} */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(
                InstantToISOConverterProvider.class,
                ContainerTypeConverterProvider.class,
                DigitalSignatureAlgorithmConverterProvider.class
        );
    }

    /** {@inheritDoc} */
    @Override
    public Set<Object> getSingletons() {
        ApplicationContext context = applicationContext;
        if (context == null) {
            throw new IllegalStateException("SECOM application context not initialised");
        }
        NiordSecomProviders providers = context.getBean(NiordSecomProviders.class);

        ObjectMapper secomMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return Set.of(
                context.getBean(SecomV2Resource.class),
                context.getBean(MrnCertificateFilter.class),
                new SecomObjectMapperProvider(secomMapper),
                new SecomWriterInterceptor(null, null, providers, providers),
                new SecomSignatureFilter(null, null, providers, providers),
                new SecomReaderInterceptor(null, null),
                new SecomV2ExceptionMapper(this)
        );
    }
}
