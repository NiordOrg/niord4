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

import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A request of the sweep, before the engine adds the identity and the request id: the verb, the path pattern of the
 * entry it is for (as the table writes it) with a value for each variable, query parameters, extra headers and a body.
 *
 * <pre>{@code
 * SweepRequest.get("/rest/sources/source/{id}").var("id", 42)
 * SweepRequest.post("/rest/sources/source").json(Map.of("descs", List.of(...)))
 * SweepRequest.post("/rest/sources/upload-sources").file("file", "sweep-sources-x.json", bytes, "application/json")
 * }</pre>
 */
public final class SweepRequest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern VARIABLE = Pattern.compile("\\{(\\*?)([A-Za-z0-9_]+)(?::[^}]*)?}");

    /** One part of a multipart body */
    public record Part(String name, String fileName, byte[] content, String contentType) {
    }

    final String verb;
    final String pattern;
    final Map<String, String> vars = new LinkedHashMap<>();
    final List<Map.Entry<String, String>> query = new ArrayList<>();
    final Map<String, String> headers = new LinkedHashMap<>();
    byte[] body;
    String contentType;
    final List<Part> parts = new ArrayList<>();
    /** The form fields of a multipart body: parts without a file name */
    final List<Map.Entry<String, String>> fields = new ArrayList<>();
    String note;
    boolean domainSet;
    String domain;

    private SweepRequest(String verb, String pattern) {
        this.verb = verb;
        this.pattern = pattern;
    }

    public static SweepRequest get(String pattern) {
        return new SweepRequest("GET", pattern);
    }

    public static SweepRequest post(String pattern) {
        return new SweepRequest("POST", pattern);
    }

    public static SweepRequest put(String pattern) {
        return new SweepRequest("PUT", pattern);
    }

    public static SweepRequest delete(String pattern) {
        return new SweepRequest("DELETE", pattern);
    }

    /** A request for the entry's key, its verb and pattern */
    public static SweepRequest of(EndpointEntry entry) {
        return new SweepRequest(entry.verb(), entry.path());
    }

    /** A request at the pattern with the same value in every path variable (a sample for the path resolution) */
    static SweepRequest sample(String verb, String pattern, String value) {
        SweepRequest request = new SweepRequest(verb, pattern);
        Matcher m = VARIABLE.matcher(pattern);
        while (m.find()) {
            request.vars.put(m.group(2), m.group(1).isEmpty() ? value : "sweep/" + value);
        }
        return request;
    }

    /** A request at another pattern of the same handler (an alias of {@code alsoAt}) */
    public SweepRequest at(String otherPattern) {
        SweepRequest copy = new SweepRequest(verb, otherPattern);
        copy.vars.putAll(vars);
        copy.query.addAll(query);
        copy.headers.putAll(headers);
        copy.body = body;
        copy.contentType = contentType;
        copy.parts.addAll(parts);
        copy.fields.addAll(fields);
        copy.note = note;
        copy.domainSet = domainSet;
        copy.domain = domain;
        return copy;
    }

    /** The value of a path variable */
    public SweepRequest var(String name, Object value) {
        vars.put(name, String.valueOf(value));
        return this;
    }

    public SweepRequest query(String name, Object value) {
        query.add(Map.entry(name, String.valueOf(value)));
        return this;
    }

    public SweepRequest header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /**
     * The {@code NiordDomain} header of this request in place of the identity's, or null for none (the token's roles
     * then come from the base client alone): a read of another domain's rows, a call the admin app makes without it.
     */
    public SweepRequest domain(String domain) {
        this.domainSet = true;
        this.domain = domain;
        return this;
    }

    /** A JSON body, written with Jackson */
    public SweepRequest json(Object value) {
        this.body = value instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : JSON.writeValueAsBytes(value);
        this.contentType = "application/json;charset=UTF-8";
        return this;
    }

    public SweepRequest body(byte[] content, String contentType) {
        this.body = content;
        this.contentType = contentType;
        return this;
    }

    /** A file part of a multipart body */
    public SweepRequest file(String name, String fileName, byte[] content, String contentType) {
        parts.add(new Part(name, fileName, content, contentType));
        this.contentType = "multipart/form-data";
        return this;
    }

    /**
     * A form field of a multipart body: a part with no file name, which a handler reads as a form parameter (for
     * example the JSON descriptor {@code data} beside the file of {@code upload-publication-file})
     */
    public SweepRequest field(String name, String value) {
        fields.add(Map.entry(name, value));
        this.contentType = "multipart/form-data";
        return this;
    }

    /** Whether the body is multipart: files, form fields, or both */
    boolean isMultipart() {
        return !parts.isEmpty() || !fields.isEmpty();
    }

    /** A note for the failure text (for example "unknown-id probe") */
    public SweepRequest note(String note) {
        this.note = note;
        return this;
    }

    public String verb() {
        return verb;
    }

    public String pattern() {
        return pattern;
    }

    public String note() {
        return note;
    }

    /** {@code "VERB pattern"}: the key of the entry the request is for, or one of its aliases */
    public String keyLike() {
        return verb + " " + pattern;
    }

    /** The concrete path: each variable replaced by its value, encoded as a path segment ({@code {*x}} keeps '/') */
    public String path() {
        Matcher m = VARIABLE.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(2));
            if (value == null) {
                throw new IllegalStateException("No value for the path variable {" + m.group(2) + "} of " + pattern);
            }
            String encoded;
            if (!m.group(1).isEmpty()) {
                // A catch-all variable follows a '/' of the pattern and spans segments: each one is encoded
                List<String> segments = new ArrayList<>();
                for (String segment : (value.startsWith("/") ? value.substring(1) : value).split("/", -1)) {
                    segments.add(UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8));
                }
                encoded = String.join("/", segments);
            } else {
                encoded = UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(encoded));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The concrete path and the query string */
    public String uri() {
        if (query.isEmpty()) {
            return path();
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> q : query) {
            pairs.add(UriUtils.encodeQueryParam(q.getKey(), StandardCharsets.UTF_8) + "="
                    + UriUtils.encodeQueryParam(q.getValue(), StandardCharsets.UTF_8));
        }
        return path() + "?" + String.join("&", pairs);
    }

    /** The body as the failure text shows it (its first characters) */
    public String bodyPreview() {
        if (isMultipart()) {
            List<String> names = new ArrayList<>();
            fields.forEach(f -> names.add(f.getKey() + " (a form field, " + f.getValue().length() + " characters)"));
            parts.forEach(p -> names.add(p.name() + "=" + p.fileName() + " (" + p.content().length + " bytes)"));
            return "multipart " + names;
        }
        if (body == null) {
            return null;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > 300 ? text.substring(0, 300) + " ..." : text;
    }

    @Override
    public String toString() {
        return verb + " " + uri();
    }
}
