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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal client for the <a href="https://github.com/mailhog/MailHog">MailHog</a> HTTP API, used by
 * the wire-level mail-delivery integration tests to assert what actually reached the SMTP server
 * (rather than only the {@code SENT}/{@code PENDING} database status).
 *
 * <p>The {@link NiordTestContainers} MailHog container exposes the SMTP port (1025) that the
 * application sends through and the HTTP API port (8025) that this client reads from. The MailHog
 * v2 read API ({@code GET /api/v2/messages}) returns the captured messages, and the v1 delete API
 * ({@code DELETE /api/v1/messages}) clears them.
 *
 * <p>Deliberately <em>framework-neutral</em>: it uses only the JDK {@link HttpClient} and Jackson
 * (a production dependency), with no Quarkus, RestAssured or CDI types, so it survives the planned
 * Quarkus-to-Spring migration unchanged.
 */
public final class MailHog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** How long {@link #waitForSubject} keeps polling before giving up (SMTP delivery is fast). */
    private static final long POLL_TIMEOUT_MS = 10_000L;
    private static final long POLL_INTERVAL_MS = 200L;

    private MailHog() {
    }

    // --------------------------------------------------------------------------------------------
    // HTTP plumbing
    // --------------------------------------------------------------------------------------------

    private static String baseUrl() {
        return NiordTestContainers.mailhogHttpUrl();
    }

    /** Deletes every captured message so each test asserts only on the mail it produced. */
    public static void clear() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/messages"))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE()
                    .build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("MailHog DELETE returned HTTP " + resp.statusCode());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed clearing MailHog", e);
        }
    }

    /** The raw {@code /api/v2/messages} document root. */
    public static JsonNode root() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v2/messages"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("MailHog API returned HTTP " + resp.statusCode());
            }
            return MAPPER.readTree(resp.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed reading MailHog messages", e);
        }
    }

    // --------------------------------------------------------------------------------------------
    // Queries
    // --------------------------------------------------------------------------------------------

    /** Total number of messages currently captured by MailHog. */
    public static int total() {
        return root().path("total").asInt();
    }

    /** A snapshot of every captured message. */
    public static List<Message> messages() {
        List<Message> out = new ArrayList<>();
        for (JsonNode item : root().path("items")) {
            out.add(new Message(item));
        }
        return out;
    }

    /** A snapshot of the captured messages whose (exact) {@code Subject} header equals {@code subject}. */
    public static List<Message> messagesWithSubject(String subject) {
        List<Message> out = new ArrayList<>();
        for (Message m : messages()) {
            if (subject.equals(m.subject())) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Polls MailHog (up to {@value #POLL_TIMEOUT_MS} ms) until at least {@code atLeast} messages with
     * the given exact {@code Subject} header have been captured, then returns all of them. If the
     * timeout elapses, returns whatever has arrived so far (which the caller asserts on).
     */
    public static List<Message> waitForSubject(String subject, int atLeast) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        List<Message> found = messagesWithSubject(subject);
        while (found.size() < atLeast && System.currentTimeMillis() < deadline) {
            sleep(POLL_INTERVAL_MS);
            found = messagesWithSubject(subject);
        }
        return found;
    }

    /** Polls for exactly one message with the subject, returning it or {@code null} after the timeout. */
    public static Message waitForOne(String subject) {
        List<Message> found = waitForSubject(subject, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --------------------------------------------------------------------------------------------
    // Captured message
    // --------------------------------------------------------------------------------------------

    /**
     * A single MailHog-captured message with convenient accessors over the v2 JSON shape:
     * <pre>
     *   { "From": {"Mailbox","Domain"}, "To": [{"Mailbox","Domain"}],
     *     "Content": { "Headers": { "Subject": [..], "To": [..], "From": [..], ... }, "Body": ".." },
     *     "Raw": { "From": "..", "To": [".."], "Data": ".." } }
     * </pre>
     */
    public static final class Message {

        private final JsonNode node;

        Message(JsonNode node) {
            this.node = node;
        }

        /** First {@code Subject} header value (verbatim for plain-ASCII subjects). */
        public String subject() {
            return header("Subject");
        }

        /** First value of the named {@code Content} header, or {@code null} if absent. */
        public String header(String name) {
            JsonNode vals = node.path("Content").path("Headers").path(name);
            return vals.isArray() && !vals.isEmpty() ? vals.get(0).asText() : null;
        }

        /** All values of the named {@code Content} header. */
        public List<String> headers(String name) {
            List<String> out = new ArrayList<>();
            for (JsonNode v : node.path("Content").path("Headers").path(name)) {
                out.add(v.asText());
            }
            return out;
        }

        /**
         * The SMTP <em>envelope</em> recipients ({@code Raw.To}) — i.e. the addresses the message was
         * actually delivered to at the wire (after recipient filtering/substitution).
         */
        public List<String> envelopeRecipients() {
            List<String> out = new ArrayList<>();
            for (JsonNode v : node.path("Raw").path("To")) {
                out.add(v.asText());
            }
            return out;
        }

        /** The parsed {@code To} addresses as {@code mailbox@domain}. */
        public List<String> toAddresses() {
            List<String> out = new ArrayList<>();
            for (JsonNode v : node.path("To")) {
                out.add(v.path("Mailbox").asText() + "@" + v.path("Domain").asText());
            }
            return out;
        }

        /** The parsed {@code From} address as {@code mailbox@domain}. */
        public String fromAddress() {
            JsonNode f = node.path("From");
            return f.path("Mailbox").asText() + "@" + f.path("Domain").asText();
        }

        /** The full raw SMTP message ({@code Raw.Data}) — headers + body, possibly quoted-printable. */
        public String rawData() {
            return node.path("Raw").path("Data").asText();
        }

        public JsonNode json() {
            return node;
        }
    }
}
