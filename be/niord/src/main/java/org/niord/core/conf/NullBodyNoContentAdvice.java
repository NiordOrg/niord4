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

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Restores the JAX-RS semantics that a resource method returning {@code null} yields {@code 204 No
 * Content}.
 *
 * <p>Under JAX-RS/Jersey a handler that returns {@code null} produces an empty {@code 204} response.
 * Spring MVC instead returns {@code 200} with an empty body for a {@code null} return value. Several
 * ported resources rely on the old behavior (e.g. {@code GET}-by-id endpoints that return {@code null}
 * for a missing entity, which the tests assert as {@code 204}). This {@link ResponseBodyAdvice} maps a
 * {@code null} body back to {@code 204} for the migrated {@code @RestController} resources, so the HTTP
 * contract is preserved without changing every call site.
 *
 * <p>It deliberately does <em>not</em> apply to {@code ResponseEntity}/{@code HttpEntity} return types
 * (those set their own status), nor does it touch a non-{@code null} body (an empty collection stays a
 * {@code 200 []}, matching JAX-RS), nor a response whose status a handler already changed away from
 * {@code 200} (e.g. via {@code @ResponseStatus}).
 */
@ControllerAdvice
public class NullBodyNoContentAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Leave ResponseEntity/HttpEntity returns alone — they carry their own status.
        return !HttpEntity.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null && response instanceof ServletServerHttpResponse servletResponse) {
            // Only upgrade the default 200 — never override a status a handler set deliberately.
            if (servletResponse.getServletResponse().getStatus() == HttpStatus.OK.value()) {
                servletResponse.getServletResponse().setStatus(HttpStatus.NO_CONTENT.value());
            }
        }
        return body;
    }
}
