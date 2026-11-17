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
package org.niord.core.integration;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.niord.core.integration.vo.MessageSeriesMappingVo;
import org.niord.core.integration.vo.NiordIntegrationVo;
import org.niord.model.message.MessageVo;
import org.niord.test.http.HttpStub;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-1 unit tests for {@link NiordIntegrationExecutionService#fetchMessages(NiordIntegrationVo)}.
 *
 * <p>The method builds the URL {@code integration.getUrl() + "/rest/public/v1/messages?" + params},
 * does an Apache-HttpClient GET, and deserializes the body into a {@code List<MessageVo>} with a default
 * Jackson {@link ObjectMapper}. These tests stand up an in-process HTTP stub ({@link HttpStub}) on an
 * ephemeral localhost port, point the service at it, and assert the observable outcomes: the returned
 * list, the recorded request line (path + query), and the exceptions thrown on non-2xx responses.
 *
 * <p>Framework-neutral: no Quarkus, no container. The service is constructed with {@code new} and its
 * injected {@code log} field is set via reflection so that it can run outside CDI.
 */
public class NiordIntegrationExecutionServiceHttpTest {

    /** Walks the class hierarchy to set a private/inherited field (used to satisfy the injected log). */
    static void set(Object target, String field, Object value) throws Exception {
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

    /** Builds the service with a real SLF4J logger, and allows the loopback stub, so it can run without CDI. */
    private static NiordIntegrationExecutionService newService() throws Exception {
        NiordIntegrationExecutionService svc =
                new NiordIntegrationExecutionService(null, null, null, null, null, null, null);
        set(svc, "log", LoggerFactory.getLogger("it"));
        // The stub necessarily binds loopback, which the importer blocks unless this @Setting is on
        set(svc, "allowPrivateHosts", true);
        return svc;
    }

    /** Builds an active integration pointed at the stub, with a single source -> target mapping. */
    private static NiordIntegrationVo integrationFor(String baseUrl, String sourceSeriesId) {
        NiordIntegrationVo vo = new NiordIntegrationVo();
        vo.setUrl(baseUrl);
        vo.setActive(true);
        MessageSeriesMappingVo m = new MessageSeriesMappingVo();
        m.setSourceSeriesId(sourceSeriesId);
        m.setTargetSeriesId("tgt");
        vo.getMessageSeriesMappings().add(m);
        return vo;
    }

    /** Serializes MessageVos with the SAME default mapper the SUT deserializes with, so the body round-trips. */
    private static String messagesJson(String... ids) throws Exception {
        java.util.List<MessageVo> msgs = new java.util.ArrayList<>();
        for (String id : ids) {
            MessageVo m = new MessageVo();
            m.setId(id);
            msgs.add(m);
        }
        return new ObjectMapper().writeValueAsString(msgs);
    }

    // ----------------------------------------------------------------------------------------- //

    /** 1) Happy path: a 2-element list is deserialized and the request line is well-formed. */
    @Test
    public void fetchMessages_returnsTwoMessages_andHitsExpectedUrl() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondJson(messagesJson("legacy-1", "legacy-2"));

            NiordIntegrationVo vo = integrationFor(stub.baseUrl(), "dma-nw");
            List<MessageVo> result = newService().fetchMessages(vo);

            assertEquals(2, result.size(), "two messages deserialized");
            assertEquals("legacy-1", result.get(0).getId());
            assertEquals("legacy-2", result.get(1).getId());

            HttpStub.RecordedRequest req = stub.lastRequest();
            assertNotNull(req, "the service must have hit the stub");
            assertEquals("GET", req.method());
            assertEquals("/rest/public/v1/messages", req.path());
            assertTrue(req.query().contains("messageSeries=dma-nw"),
                    "query carries the source series: " + req.query());
            assertEquals(1, stub.requestCount(), "exactly one outbound request");
        }
    }

    /** 2) Empty list: an empty JSON array yields an empty (non-null) result list. */
    @Test
    public void fetchMessages_emptyArray_returnsEmptyList() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondJson("[]");

            List<MessageVo> result = newService().fetchMessages(integrationFor(stub.baseUrl(), "dma-nw"));

            assertNotNull(result);
            assertEquals(0, result.size(), "empty array -> empty list");
        }
    }

    /** 3) URL construction: every mapping contributes its own messageSeries query parameter. */
    @Test
    public void fetchMessages_multipleMappings_encodeAllSourceSeries() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondJson("[]");

            NiordIntegrationVo vo = new NiordIntegrationVo();
            vo.setUrl(stub.baseUrl());
            vo.setActive(true);
            MessageSeriesMappingVo a = new MessageSeriesMappingVo();
            a.setSourceSeriesId("a");
            a.setTargetSeriesId("tgt");
            MessageSeriesMappingVo b = new MessageSeriesMappingVo();
            b.setSourceSeriesId("b");
            b.setTargetSeriesId("tgt");
            vo.getMessageSeriesMappings().add(a);
            vo.getMessageSeriesMappings().add(b);

            newService().fetchMessages(vo);

            String query = stub.lastRequest().query();
            assertTrue(query.contains("messageSeries=a"), "query carries source 'a': " + query);
            assertTrue(query.contains("messageSeries=b"), "query carries source 'b': " + query);
            assertTrue(query.contains("&"), "the two params are joined with '&': " + query);
        }
    }

    /** 4) Server error: a 500 response must surface as an Exception naming the status. */
    @Test
    public void fetchMessages_serverError500_throwsWithStatusInMessage() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondStatus(500);

            NiordIntegrationVo vo = integrationFor(stub.baseUrl(), "dma-nw");
            NiordIntegrationExecutionService svc = newService();

            Exception ex = assertThrows(Exception.class, () -> svc.fetchMessages(vo));
            assertTrue(ex.getMessage().contains("status = 500"),
                    "exception message reports the HTTP status: " + ex.getMessage());
        }
    }

    /** 5) Not found: a 404 response must also surface as an Exception naming the status. */
    @Test
    public void fetchMessages_notFound404_throws() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondStatus(404);

            NiordIntegrationVo vo = integrationFor(stub.baseUrl(), "dma-nw");
            NiordIntegrationExecutionService svc = newService();

            Exception ex = assertThrows(Exception.class, () -> svc.fetchMessages(vo));
            assertTrue(ex.getMessage().contains("404"),
                    "exception message reports the HTTP status: " + ex.getMessage());
        }
    }

    /**
     * 6) SSRF guard (SECAUD-9): with the setting at its default the loopback stub is never contacted.
     * Asserting the stub's request count — not just the exception — is what proves the guard fires
     * before any byte leaves the process, which is the whole point of blocking an internal target.
     */
    @Test
    public void fetchMessages_loopbackTargetIsBlockedWhenPrivateHostsAreNotAllowed() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondJson("[]");

            NiordIntegrationExecutionService svc = newService();
            set(svc, "allowPrivateHosts", false);

            assertThrows(Exception.class, () -> svc.fetchMessages(integrationFor(stub.baseUrl(), "dma-nw")));
            assertEquals(0, stub.requestCount(), "a blocked target must not be contacted at all");
        }
    }

    /**
     * 8) Characterization: a base URL with a trailing slash still reaches the intended endpoint. The
     * endpoint path is appended as a raw string, so this composes to "//rest/public/v1/messages" — but
     * Apache HttpClient normalizes the double slash away before the request is sent, so the peer sees
     * the intended path. This is why the validator neither rejects nor trims a trailing slash; the test
     * exists so that a future change (a different client, a manual URL build) cannot break it silently.
     */
    @Test
    public void fetchMessages_trailingSlashInBaseUrlStillHitsTheEndpoint() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondJson(messagesJson("legacy-1"));

            List<MessageVo> result = newService().fetchMessages(integrationFor(stub.baseUrl() + "/", "dma-nw"));

            assertEquals(1, result.size());
            HttpStub.RecordedRequest req = stub.lastRequest();
            assertEquals("/rest/public/v1/messages", req.path(),
                    "the trailing slash must not leak into the request path");
            assertEquals("messageSeries=dma-nw", req.query());
        }
    }

    /** 9) A URL carrying a query or fragment is rejected: the endpoint path could not be appended to it. */
    @Test
    public void fetchMessages_urlWithQueryOrFragmentIsRejected() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondJson("[]");
            NiordIntegrationExecutionService svc = newService();

            assertThrows(IllegalArgumentException.class,
                    () -> svc.fetchMessages(integrationFor(stub.baseUrl() + "/x?a=b", "dma-nw")));
            assertThrows(IllegalArgumentException.class,
                    () -> svc.fetchMessages(integrationFor(stub.baseUrl() + "#frag", "dma-nw")));
            assertEquals(0, stub.requestCount(),
                    "a URL that cannot be composed correctly must not be fetched at all");
        }
    }

    /** 7) A URL carrying credentials is rejected before the request is built, so they never hit the wire. */
    @Test
    public void fetchMessages_urlWithUserInfoIsRejected() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondJson("[]");
            String url = stub.baseUrl().replace("http://", "http://user:pw@");

            NiordIntegrationExecutionService svc = newService();

            Exception ex = assertThrows(IllegalArgumentException.class,
                    () -> svc.fetchMessages(integrationFor(url, "dma-nw")));
            assertTrue(ex.getMessage().contains("user info"), ex.getMessage());
            assertEquals(0, stub.requestCount(), "the credentials must never be put on the wire");
        }
    }
}
