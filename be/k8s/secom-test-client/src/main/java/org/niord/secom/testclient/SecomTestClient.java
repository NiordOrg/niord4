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
package org.niord.secom.testclient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.grad.secomv2.core.base.DigitalSignatureCertificate;
import org.grad.secomv2.core.base.SecomCertificateProvider;
import org.grad.secomv2.core.base.SecomSignatureProvider;
import org.grad.secomv2.core.interfaces.AcknowledgementServiceInterface;
import org.grad.secomv2.core.interfaces.SearchServiceServiceInterface;
import org.grad.secomv2.core.interfaces.SubscriptionNotificationServiceInterface;
import org.grad.secomv2.core.interfaces.SubscriptionServiceInterface;
import org.grad.secomv2.core.interfaces.UploadServiceInterface;
import org.grad.secomv2.core.models.AcknowledgementObject;
import org.grad.secomv2.core.models.DigitalSignatureValueObject;
import org.grad.secomv2.core.models.EnvelopeAckObject;
import org.grad.secomv2.core.models.EnvelopeUploadObject;
import org.grad.secomv2.core.models.SubscriptionNotificationObject;
import org.grad.secomv2.core.models.UploadObject;
import org.grad.secomv2.core.models.enums.AckTypeEnum;
import org.grad.secomv2.core.models.enums.DigitalSignatureAlgorithmEnum;
import org.grad.secomv2.core.utils.SecomPemUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal SECOM consumer used to rehearse the Niord S-124 producer flow end-to-end on the
 * local Kubernetes deployment: it subscribes to the Niord SECOM service with the committed
 * test-PKI client identity, doubles as its own MCP service registry (so Niord can resolve
 * this node's endpoint without a real MSR), receives pushed uploads on {@code /v2/object},
 * verifies the envelope + payload signatures, and answers with a signed DELIVERED
 * acknowledgement - driving Niord's outbox entry to ACKED.
 *
 * <p>Waits for the envelope work: the client still presents its identity as a TLS client
 * certificate, which the chart's plain {@code /api/secom} endpoint never sees (SECOM
 * Edition 2 carries the peer certificate in the signed envelope, and the app's filter does
 * not read it yet - docs/s124-secom-spec-gap-analysis.md §7), so the subscribe loop is
 * refused with 403 until both sides sign and read envelopes. The push/ack half works once
 * a subscription exists.
 *
 * <p>Configuration is by environment variable; the defaults match the k8s.yaml manifest and
 * the committed test PKI (client-one). Received payloads are dumped to DATA_DIR.
 *
 * <p>Deliberate test-tool shortcuts, fine here and nowhere else: the outbound TLS trust is
 * trust-all (for a TLS endpoint with a throwaway certificate; unused over plain http), and
 * the node serves plain HTTP for the pushes it receives (Niord's outbound client speaks
 * HTTP for http:// endpoints, sidestepping peer-server-certificate issuance which the
 * committed test PKI cannot do - its root key is not checked in).
 */
public class SecomTestClient {

    /** SECOM wire JSON is Jackson 2 with ISO timestamps (matches the Niord servlet's mapper) */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    final int port = intEnv("PORT", 8080);
    final String niordSecomUrl = env("NIORD_SECOM_URL", "http://niord-proxy:18888/api/secom");
    final String ownEndpoint = env("OWN_ENDPOINT", "http://secom-test-client:8080");
    final String mrn = env("CLIENT_MRN", "urn:mrn:mcp:service:mcc:test:instance:client-one");
    final String keystorePath = env("KEYSTORE", "/run/secrets/secom-client/client-one.p12");
    final String keystorePassword = env("KEYSTORE_PASSWORD", "changeit");
    final String keystoreAlias = env("KEYSTORE_ALIAS", "1");
    final String truststorePath = env("TRUSTSTORE", "/run/secrets/secom-client/truststore.p12");
    final boolean subscribeOnStart = Boolean.parseBoolean(env("SUBSCRIBE", "true"));
    final String subscribeWkt = env("SUBSCRIBE_WKT", "");
    final String subscribeBodyOverride = env("SUBSCRIBE_BODY", "");
    final boolean sendOpenedAck = Boolean.parseBoolean(env("SEND_OPENED_ACK", "false"));
    final Path dataDir = Path.of(env("DATA_DIR", "/data"));

    final Signer signer;
    final HttpClient niordClient;
    final ExecutorService ackExecutor = Executors.newSingleThreadExecutor();

    public static void main(String[] args) throws Exception {
        new SecomTestClient().run();
    }

    SecomTestClient() throws Exception {
        KeyStore keystore = loadStore(keystorePath, keystorePassword);
        KeyStore truststore = loadStore(truststorePath, keystorePassword);
        X509Certificate clientCert = (X509Certificate) keystore.getCertificate(keystoreAlias);
        PrivateKey clientKey = (PrivateKey) keystore.getKey(keystoreAlias, keystorePassword.toCharArray());
        X509Certificate root = null;
        for (var aliases = truststore.aliases(); aliases.hasMoreElements(); ) {
            if (truststore.getCertificate(aliases.nextElement()) instanceof X509Certificate x509) {
                root = x509;
            }
        }
        DigitalSignatureAlgorithmEnum ackAlgorithm =
                DigitalSignatureAlgorithmEnum.valueOf(env("ACK_ALGORITHM", "SHA2_384_WITH_ECDSA"));
        this.signer = new Signer(clientCert, clientKey, root, ackAlgorithm);
        this.niordClient = newNiordClient(keystore, keystorePassword);
        log("client identity: " + clientCert.getSubjectX500Principal() + " (MRN " + mrn + ")");
    }

    void run() throws Exception {
        Files.createDirectories(dataDir);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handle);
        server.start();
        log("listening on :" + port + ", advertising endpoint " + ownEndpoint);

        if (subscribeOnStart) {
            Thread.ofPlatform().daemon().start(this::subscribeUntilAccepted);
        }
    }

    /***************************************/
    /** Server side                       **/
    /***************************************/

    void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        byte[] body = exchange.getRequestBody().readAllBytes();
        try {
            if ("GET".equals(method) && "/healthz".equals(path)) {
                respond(exchange, 200, Map.of("status", "up"));
            } else if ("POST".equals(method) && path.endsWith(SearchServiceServiceInterface.SEARCH_SERVICE_INTERFACE_PATH)) {
                log("MSR search served -> instanceId " + mrn + " @ " + ownEndpoint);
                respond(exchange, 200, Map.of("envelope", Map.of("serviceInstance",
                        java.util.List.of(Map.of("instanceId", mrn, "endpointUri", ownEndpoint)))));
            } else if ("POST".equals(method) && path.endsWith(SubscriptionNotificationServiceInterface.SUBSCRIPTION_NOTIFICATION_INTERFACE_PATH)) {
                SubscriptionNotificationObject notification = MAPPER.readValue(body, SubscriptionNotificationObject.class);
                log("SUBSCRIPTION NOTIFICATION: " + notification.getEnvelope().getEventEnum()
                        + " for subscription " + notification.getEnvelope().getSubscriptionIdentifier());
                respond(exchange, 200, Map.of("message", "noted"));
            } else if ("POST".equals(method) && path.endsWith(UploadServiceInterface.UPLOAD_INTERFACE_PATH)) {
                onUpload(exchange, body);
            } else {
                log(method + " " + path + " -> 404");
                respond(exchange, 404, Map.of("message", "unknown path " + path));
            }
        } catch (Exception e) {
            log("ERROR handling " + method + " " + path + ": " + e);
            respond(exchange, 400, Map.of("message", String.valueOf(e)));
        }
    }

    /** The heart of the rehearsal: a pushed SECOM upload from Niord */
    void onUpload(HttpExchange exchange, byte[] body) throws Exception {
        UploadObject upload = MAPPER.readValue(body, UploadObject.class);
        EnvelopeUploadObject envelope = (EnvelopeUploadObject) upload.getEnvelope();
        UUID transactionId = envelope.getTransactionIdentifier();
        log("UPLOAD received: transaction " + transactionId
                + ", subscription " + envelope.getSubscriptionIdentifier()
                + ", containerType " + envelope.getContainerType()
                + ", product " + envelope.getDataProductType()
                + ", ackRequest " + envelope.getAckRequest());

        verifyEnvelopeSignature(upload, envelope);
        byte[] payload = decodePayload(envelope);
        verifyPayloadSignature(envelope, payload);
        describeAndStore(transactionId, payload);

        // Answer first, then acknowledge on a separate connection (like a real consumer;
        // Niord tolerates the ack racing its own dispatch bookkeeping either way)
        respond(exchange, 200, Map.of("message", "upload received"));
        if (envelope.getAckRequest() != null && !envelope.getAckRequest().name().startsWith("NO_")) {
            String callback = envelope.getCallbackEndpoint() != null
                    ? envelope.getCallbackEndpoint().toString()
                    : niordSecomUrl;
            ackExecutor.submit(() -> acknowledge(callback, transactionId, AckTypeEnum.DELIVERED_ACK));
            if (sendOpenedAck) {
                ackExecutor.submit(() -> acknowledge(callback, transactionId, AckTypeEnum.OPENED_ACK));
            }
        }
    }

    /** The envelope signature covers the envelope's canonical CSV form (UTF-8) */
    void verifyEnvelopeSignature(UploadObject upload, EnvelopeUploadObject envelope) {
        try {
            String[] certificates = envelope.getEnvelopeSignatureCertificate();
            byte[] signature = decodeSignature(upload.getEnvelopeSignature());
            byte[] content = envelope.getCsvString().getBytes(StandardCharsets.UTF_8);
            X509Certificate cert = SecomPemUtils.getCertFromPem(certificates[0]);
            String verified = verifiesWithEither(cert, signature, content);
            log(verified != null
                    ? "envelope signature VERIFIED (" + verified + ") - signer " + cert.getSubjectX500Principal()
                    : "envelope signature FAILED verification (tried SHA2-384 and SHA3-384 ECDSA)");
        } catch (Exception e) {
            log("envelope signature could not be checked: " + e);
        }
    }

    /**
     * The payload signature was applied to the raw data before its base64 transfer encoding
     * (SECOMLib signData -> encodeData order); the declared algorithm rides in the metadata.
     */
    void verifyPayloadSignature(EnvelopeUploadObject envelope, byte[] payload) {
        try {
            DigitalSignatureValueObject dsv = envelope.getExchangeMetadata().getDigitalSignatureValue();
            X509Certificate cert = SecomPemUtils.getCertFromPem(dsv.getPublicCertificate()[0]);
            byte[] signature = decodeSignature(dsv.getDigitalSignature());
            DigitalSignatureAlgorithmEnum declared = envelope.getExchangeMetadata().getDigitalSignatureReference();
            String verified = null;
            for (byte[] content : new byte[][]{ payload, envelope.getData() }) {
                verified = verifiesWithEither(cert, signature, content, declared);
                if (verified != null) {
                    break;
                }
            }
            log(verified != null
                    ? "payload signature VERIFIED (" + verified + ", declared " + declared + ") - signer "
                            + cert.getSubjectX500Principal()
                    : "payload signature FAILED verification (declared " + declared + ")");
        } catch (Exception e) {
            log("payload signature could not be checked: " + e);
        }
    }

    /** Unwraps the explicit base64 transfer encoding (on top of Jackson's byte[] base64) */
    byte[] decodePayload(EnvelopeUploadObject envelope) {
        byte[] data = envelope.getData();
        try {
            return Base64.getDecoder().decode(new String(data, StandardCharsets.UTF_8).trim());
        } catch (IllegalArgumentException notDoubleEncoded) {
            return data;
        }
    }

    /** Logs what arrived and keeps a copy in DATA_DIR for later inspection */
    void describeAndStore(UUID transactionId, byte[] payload) {
        try {
            boolean zip = payload.length > 2 && payload[0] == 'P' && payload[1] == 'K';
            Path file = dataDir.resolve("tx-" + transactionId + (zip ? ".zip" : ".gml"));
            Files.write(file, payload);
            if (zip) {
                log("payload is an S-100 exchange set (" + payload.length + " bytes) -> " + file);
                try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(payload))) {
                    for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                        byte[] content = in.readAllBytes();
                        log("  - " + entry.getName() + " (" + content.length + " bytes)"
                                + (entry.getName().toUpperCase().endsWith(".GML") ? ": " + gmlSummary(content) : ""));
                    }
                }
            } else {
                log("payload is a bare dataset (" + payload.length + " bytes) -> " + file + ": " + gmlSummary(payload));
            }
        } catch (Exception e) {
            log("could not describe/store the payload: " + e);
        }
    }

    /** A one-line orientation of a GML dataset: its id and the first title-ish element */
    static String gmlSummary(byte[] gml) {
        String text = new String(gml, StandardCharsets.UTF_8);
        String id = firstMatch(text, "gml:id=\"([^\"]+)\"");
        String title = firstMatch(text, "<[Ss]124:text>([^<]+)<");
        return "gml:id=" + id + (title != null ? ", text=\"" + title + "\"" : "");
    }

    static String firstMatch(String text, String regex) {
        var matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /***************************************/
    /** Client side (towards Niord)       **/
    /***************************************/

    /** Subscribes to the Niord SECOM service; retried while the app is still booting */
    void subscribeUntilAccepted() {
        String body = !subscribeBodyOverride.isBlank()
                ? subscribeBodyOverride
                : subscribeWkt.isBlank()
                    ? """
                      { "envelope": { "dataProductType": "S-124", "productVersion": "2.0.0" }}"""
                    : """
                      { "envelope": { "dataProductType": "S-124", "productVersion": "2.0.0", "geometry": "%s" }}"""
                            .formatted(subscribeWkt);
        String url = niordSecomUrl + SubscriptionServiceInterface.SUBSCRIPTION_INTERFACE_PATH;
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<String> response = post(url, body);
                if (response.statusCode() == 200) {
                    String subscriptionId = MAPPER.readTree(response.body()).path("subscriptionIdentifier").asText();
                    log("SUBSCRIBED: " + subscriptionId + " (attempt " + attempt + ") - awaiting pushes");
                    return;
                }
                log("subscribe attempt " + attempt + " -> HTTP " + response.statusCode() + ": " + response.body());
            } catch (Exception e) {
                log("subscribe attempt " + attempt + " failed: " + e);
            }
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Sends a signed acknowledgement for the transaction (the exact IT-proven sequence) */
    void acknowledge(String callbackEndpoint, UUID transactionId, AckTypeEnum ackType) {
        try {
            EnvelopeAckObject envelope = new EnvelopeAckObject();
            envelope.setCreatedAt(Instant.now());
            envelope.setTransactionIdentifier(transactionId);
            envelope.setAckType(ackType);
            AcknowledgementObject ack = new AcknowledgementObject();
            ack.setEnvelope(envelope);
            ack.signEnvelope(signer, signer);

            String url = callbackEndpoint.replaceAll("/$", "")
                    + AcknowledgementServiceInterface.ACKNOWLEDGMENT_INTERFACE_PATH;
            HttpResponse<String> response = post(url, MAPPER.writeValueAsString(ack));
            log(ackType + " for transaction " + transactionId + " -> HTTP " + response.statusCode()
                    + ": " + response.body());
        } catch (Exception e) {
            log("could not acknowledge transaction " + transactionId + ": " + e);
        }
    }

    HttpResponse<String> post(String url, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return niordClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /***************************************/
    /** Crypto plumbing                   **/
    /***************************************/

    /** Signs SECOM envelopes as the test client; mirrors the IT suite's ClientSigner */
    record Signer(X509Certificate certificate, PrivateKey key, X509Certificate root,
                  DigitalSignatureAlgorithmEnum algorithm)
            implements SecomCertificateProvider, SecomSignatureProvider {

        @Override
        public DigitalSignatureCertificate getDigitalSignatureCertificate() {
            DigitalSignatureCertificate cert = new DigitalSignatureCertificate();
            cert.setCertificateAlias(new String[] { "1" });
            cert.setCertificate(new X509Certificate[] { certificate });
            cert.setPublicKey(certificate.getPublicKey());
            cert.setRootCertificate(root);
            return cert;
        }

        @Override
        public DigitalSignatureAlgorithmEnum getSignatureAlgorithm() {
            return algorithm;
        }

        @Override
        public byte[] generateSignature(DigitalSignatureCertificate signatureCertificate, byte[] payload) {
            try {
                Signature signature = Signature.getInstance(getSignatureAlgorithm().getValue());
                signature.initSign(key);
                signature.update(payload);
                return signature.sign();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean validateSignature(String[] signatureCertificates, byte[] signature, byte[] content) {
            try {
                X509Certificate cert = SecomPemUtils.getCertFromPem(signatureCertificates[0]);
                Signature verification = Signature.getInstance(getSignatureAlgorithm().getValue());
                verification.initVerify(cert);
                verification.update(content);
                return verification.verify(signature);
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** Tries the two 384-bit ECDSA variants the peer fleet is split between */
    static String verifiesWithEither(X509Certificate cert, byte[] signature, byte[] content) {
        return verifiesWithEither(cert, signature, content, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA);
    }

    static String verifiesWithEither(X509Certificate cert, byte[] signature, byte[] content,
                                     DigitalSignatureAlgorithmEnum first) {
        if (first == null) {
            first = DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA;
        }
        DigitalSignatureAlgorithmEnum other = first == DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA
                ? DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA
                : DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA;
        for (DigitalSignatureAlgorithmEnum algorithm : new DigitalSignatureAlgorithmEnum[]{ first, other }) {
            try {
                Signature verification = Signature.getInstance(algorithm.getValue());
                verification.initVerify(cert);
                verification.update(content);
                if (verification.verify(signature)) {
                    return algorithm.name();
                }
            } catch (Exception ignored) {
                // fall through to the other variant
            }
        }
        return null;
    }

    /** SECOM signature strings appear as hex or base64 depending on the producer; accept both */
    static byte[] decodeSignature(String signature) {
        String trimmed = signature.trim();
        if (trimmed.matches("(?i)[0-9a-f]+") && trimmed.length() % 2 == 0) {
            return HexFormat.of().parseHex(trimmed);
        }
        return Base64.getDecoder().decode(trimmed);
    }

    /***************************************/
    /** Bootstrap helpers                 **/
    /***************************************/

    static KeyStore loadStore(String path, String password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            store.load(in, password.toCharArray());
        }
        return store;
    }

    /**
     * The client towards Niord: offers the test client identity as a TLS client certificate
     * if a TLS endpoint asks for one (the chart's does not since Edition 2; over the plain
     * in-cluster endpoint this context is never used), and accepts any server certificate
     * (trust-all - TEST ONLY).
     */
    static HttpClient newNiordClient(KeyStore keystore, String password) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keystore, password.toCharArray());
        X509ExtendedTrustManager trustAll = new X509ExtendedTrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), new TrustManager[]{ trustAll }, new SecureRandom());
        return HttpClient.newBuilder()
                .sslContext(context)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() ? value : fallback;
    }

    static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() ? Integer.parseInt(value) : fallback;
    }

    static void log(String message) {
        System.out.println(Instant.now() + " [secom-test-client] " + message);
    }
}
