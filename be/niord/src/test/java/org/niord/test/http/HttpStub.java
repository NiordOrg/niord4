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
package org.niord.test.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A tiny, dependency-free outbound-HTTP stub for tests.
 *
 * <p><b>Why this exists.</b> Several Niord classes call out to the network over plain HTTP — the remote
 * Niord sync ({@code NiordIntegrationExecutionService}), the WMS tile proxy ({@code WmsProxyServlet}), the
 * map-image tile fetch ({@code TileFetcher}), the Twitter client
 * ({@code TwitterPromulgationService}) and the Keycloak admin
 * client ({@code KeycloakIntegrationService}). Until now the test suite had no way to stand up an HTTP
 * endpoint, so only the failure / degraded branches of those classes were ever asserted. This fixture
 * fills that gap.
 *
 * <p><b>No new dependency.</b> It is built on {@link com.sun.net.httpserver.HttpServer} from the JDK's
 * {@code jdk.httpserver} module, so it adds nothing to any {@code pom.xml} and is entirely
 * framework-neutral — it does not depend on Quarkus and will survive the planned Spring migration
 * unchanged.
 *
 * <p><b>Usage.</b> Start it (it binds an ephemeral port), point the class-under-test at {@link #baseUrl()},
 * configure a response, exercise the code, then assert against the {@linkplain #requests() recorded
 * requests}:
 * <pre>{@code
 * try (HttpStub stub = HttpStub.start()) {
 *     stub.respondJson("[]");
 *     // ... drive the code at stub.baseUrl() ...
 *     assertEquals("/rest/public/v1/messages", stub.lastRequest().path());
 * }
 * }</pre>
 *
 * <p>Every request is fully drained and recorded before the response is written, so request bodies can be
 * asserted even for POST/PUT. The default response is {@code 200} with an empty body. Responders may branch
 * on the {@link RecordedRequest} (path, method, query, headers) to serve different routes.
 *
 * <p>Not thread-safe to reconfigure mid-flight, but request recording is synchronized so a responder and
 * the asserting test thread can safely share the list.
 */
public final class HttpStub implements AutoCloseable {

    /** A canned response: status code, content type (nullable), extra headers and body bytes. */
    public static final class StubResponse {
        final int status;
        final String contentType;
        final byte[] body;
        final Map<String, String> headers;

        private StubResponse(int status, String contentType, byte[] body) {
            this(status, contentType, body, Map.of());
        }

        private StubResponse(int status, String contentType, byte[] body, Map<String, String> headers) {
            this.status = status;
            this.contentType = contentType;
            this.body = body != null ? body : new byte[0];
            this.headers = headers;
        }

        /** A redirect with the given status (301, 302, 303, 307 or 308) to the given location. */
        public static StubResponse redirect(int status, String location) {
            return new StubResponse(status, null, new byte[0], Map.of("Location", location));
        }

        public static StubResponse of(int status, String contentType, byte[] body) {
            return new StubResponse(status, contentType, body);
        }

        /** A response with extra headers, e.g. {@code ETag} and {@code Cache-Control} for a caching client. */
        public static StubResponse of(int status, String contentType, byte[] body, Map<String, String> headers) {
            return new StubResponse(status, contentType, body, headers);
        }

        public static StubResponse json(String json) {
            return new StubResponse(200, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }

        public static StubResponse text(int status, String text) {
            return new StubResponse(status, "text/plain", text.getBytes(StandardCharsets.UTF_8));
        }

        public static StubResponse bytes(String contentType, byte[] body) {
            return new StubResponse(200, contentType, body);
        }

        public static StubResponse status(int status) {
            return new StubResponse(status, null, new byte[0]);
        }
    }

    /** A request as observed by the stub. */
    public static final class RecordedRequest {
        private final String method;
        private final String path;
        private final String query;
        private final Map<String, List<String>> headers;
        private final byte[] body;

        RecordedRequest(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        public String method()  { return method; }
        public String path()    { return path; }
        public String query()   { return query; }
        public byte[] body()    { return body; }
        public String bodyString() { return new String(body, StandardCharsets.UTF_8); }

        /** Returns the first value of the named header (case-insensitive), or null. */
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return method + " " + path + (query != null ? "?" + query : "");
        }
    }

    private final HttpServer server;
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<RecordedRequest, StubResponse> responder = req -> StubResponse.status(200);

    private HttpStub(HttpServer server) {
        this.server = server;
    }

    /** Starts a stub bound to an ephemeral localhost port. */
    public static HttpStub start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpStub stub = new HttpStub(server);
        server.createContext("/", stub::handle);
        server.setExecutor(null); // default single-threaded executor is fine for tests
        server.start();
        return stub;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            RecordedRequest req = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders(),
                    body);
            requests.add(req);

            StubResponse resp;
            try {
                resp = responder.apply(req);
            } catch (RuntimeException e) {
                resp = StubResponse.text(500, "stub responder failed: " + e);
            }
            if (resp == null) {
                resp = StubResponse.status(404);
            }

            if (resp.contentType != null) {
                exchange.getResponseHeaders().set("Content-Type", resp.contentType);
            }
            resp.headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            if (resp.body.length == 0) {
                // -1 => no response body (and avoids a 0-length chunked frame)
                exchange.sendResponseHeaders(resp.status, -1);
            } else {
                exchange.sendResponseHeaders(resp.status, resp.body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(resp.body);
                }
            }
        } finally {
            exchange.close();
        }
    }

    // --------------------------------------------------------------------- //
    // Configuration
    // --------------------------------------------------------------------- //

    /** Routes every request through the given function (full control, may branch on the request). */
    public HttpStub respond(Function<RecordedRequest, StubResponse> responder) {
        this.responder = responder;
        return this;
    }

    /** Always answers with the given canned response. */
    public HttpStub respondWith(StubResponse response) {
        this.responder = req -> response;
        return this;
    }

    /** Always answers {@code 200 application/json} with the given JSON body. */
    public HttpStub respondJson(String json) {
        return respondWith(StubResponse.json(json));
    }

    /** Always answers {@code 200} with the given content type and bytes. */
    public HttpStub respondBytes(String contentType, byte[] body) {
        return respondWith(StubResponse.bytes(contentType, body));
    }

    /** Always answers with the given status and an empty body. */
    public HttpStub respondStatus(int status) {
        return respondWith(StubResponse.status(status));
    }

    // --------------------------------------------------------------------- //
    // Inspection
    // --------------------------------------------------------------------- //

    /** The base URL, e.g. {@code http://127.0.0.1:54321} (no trailing slash). */
    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** A snapshot of all requests received so far, in arrival order. */
    public List<RecordedRequest> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    public int requestCount() {
        return requests.size();
    }

    /** The most recently received request, or null if none. */
    public RecordedRequest lastRequest() {
        synchronized (requests) {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }
    }

    /** Clears the recorded requests (the responder is unchanged) — for stubs shared across tests. */
    public void reset() {
        synchronized (requests) {
            requests.clear();
        }
    }

    /** The first recorded request whose path equals the given value, or null. */
    public RecordedRequest firstRequestForPath(String path) {
        synchronized (requests) {
            for (RecordedRequest r : requests) {
                if (r.path().equals(path)) {
                    return r;
                }
            }
        }
        return null;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
