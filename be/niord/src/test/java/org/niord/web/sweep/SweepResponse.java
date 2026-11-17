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
package org.niord.web.sweep;

import io.restassured.path.json.JsonPath;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The answer to a sweep request, with what the failure text needs: the request, its id, the identity and the time.
 */
public record SweepResponse(SweepRequest request, SweepIdentity identity, String requestId, int status,
                            Map<String, List<String>> headers, byte[] body, long sentMillis, long receivedMillis) {

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** The answer as JSON (RestAssured's path syntax: {@code json().getString("descs[0].name")}) */
    public JsonPath json() {
        return JsonPath.from(text());
    }

    public String contentType() {
        return header("Content-Type");
    }

    public String header(String name) {
        for (Map.Entry<String, List<String>> h : headers.entrySet()) {
            if (h.getKey().equalsIgnoreCase(name) && !h.getValue().isEmpty()) {
                return h.getValue().getFirst();
            }
        }
        return null;
    }

    public boolean is2xx() {
        return status >= 200 && status < 300;
    }

    /** The detail sentence of a problem body ({@code application/problem+json}), else null */
    public String detail() {
        String type = contentType();
        if (body.length == 0 || type == null || !type.contains("json") || !text().stripLeading().startsWith("{")) {
            return null;
        }
        try {
            Object detail = json().get("detail");
            return detail == null ? null : String.valueOf(detail);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The refusal sentence of a publication catalogue body, else null: {@code PublicationExceptionAdvice} answers a
     * publication failure with a JSON object of a {@code code} and a {@code message} (a published contract, not a
     * problem detail), which {@link #detail()} does not read. Answered as {@code "<code>: <message>"}, so a cell's
     * detail may name the code ({@code "NOT_IN_DOMAIN"}), the sentence, or both. Null for a body with a
     * {@code detail}, or without a text {@code code} and {@code message}.
     */
    public String catalogueDetail() {
        String type = contentType();
        if (body.length == 0 || type == null || !type.contains("json") || !text().stripLeading().startsWith("{")) {
            return null;
        }
        try {
            JsonPath json = json();
            Object detail = json.get("detail");
            Object code = json.get("code");
            Object message = json.get("message");
            if (detail != null || !(code instanceof String c) || !(message instanceof String m)) {
                return null;
            }
            return c + ": " + m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The first characters of the answer, for the failure text */
    public String preview() {
        String text = text();
        return text.length() > 300 ? text.substring(0, 300) + " ..." : text;
    }

    public long millis() {
        return receivedMillis - sentMillis;
    }
}
