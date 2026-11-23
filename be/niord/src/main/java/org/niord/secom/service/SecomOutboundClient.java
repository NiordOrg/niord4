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
package org.niord.secom.service;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.grad.secomv2.core.base.SecomCertificateProvider;
import org.grad.secomv2.core.base.SecomSignatureProvider;
import org.grad.secomv2.core.interfaces.SearchServiceServiceInterface;
import org.grad.secomv2.core.interfaces.SubscriptionNotificationServiceInterface;
import org.grad.secomv2.core.interfaces.UploadServiceInterface;
import org.grad.secomv2.core.models.EnvelopeSearchFilterObject;
import org.grad.secomv2.core.models.EnvelopeSearchResultObject;
import org.grad.secomv2.core.models.SearchFilterObject;
import org.grad.secomv2.core.models.SearchParameters;
import org.grad.secomv2.core.models.SearchResult;
import org.grad.secomv2.core.models.ServiceInstanceObject;
import org.grad.secomv2.core.models.SubscriptionNotificationObject;
import org.grad.secomv2.core.models.UploadObject;
import org.niord.core.integration.NiordIntegrationUrlValidator;
import org.niord.core.settings.SettingChangedEvent;
import org.niord.core.settings.SettingsService;
import org.niord.core.settings.annotation.Setting;
import org.niord.secom.SecomConfiguration;
import org.niord.secom.pki.CertificateRenewalUtils;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.pki.NiordSecomProviders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import static org.niord.core.settings.Setting.Type;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbound SECOM plumbing: resolves a peer MRN to its service endpoint via the MCP Service
 * Registry (MSR), and POSTs signed SECOM objects (upload, subscription notification) to it.
 * <p>
 * All conformance-sensitive behavior — exchange-metadata preparation, payload signing,
 * base64 encoding and envelope signing — is delegated to the SECOMLib core model helpers,
 * the exact code paths the GRAD {@code SecomClient} uses. Only the transport differs: a JDK
 * {@link HttpClient}, which avoids dragging the webflux/reactor stack (and the Boot-3-only
 * RESTEasy starter) into Niord. Our identity towards the peer is the signed envelope (SECOM
 * Edition 2); the client still offers the MCP service certificate as a TLS client certificate,
 * which an Edition 2 peer ignores (docs/s124-secom-spec-gap-analysis.md §7).
 * <p>
 * The peer endpoints come from the registry, so they are checked before anything is sent to
 * them: only the instance registered under the peer's own MRN counts, its endpoint must be an
 * https URL without user-info, query or fragment ({@link #validatePeerEndpoint}), and its host
 * must not resolve to a loopback, link-local or private address ({@link #requirePublicAddresses}).
 * The {@code secomAllowInsecureEndpoints} setting relaxes the last two for test environments,
 * whose peers run on plain http inside the cluster. Every exchange also has an overall deadline
 * ({@link #requestTimeout}), so a slow peer cannot hold the dispatcher, and the scheduler thread it runs
 * on, for good.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class SecomOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(SecomOutboundClient.class);

    /**
     * The overall deadline of one outbound exchange: connect, TLS handshake, request and the
     * whole response body. {@link HttpRequest.Builder#timeout} alone stops counting once the
     * response headers have arrived, so a peer that sends its headers and then trickles the body
     * would otherwise block the dispatcher - and with it one of the four scheduler threads every
     * {@code @Scheduled} job shares - for good. Package-private so the ITs can shorten it.
     */
    Duration requestTimeout = Duration.ofSeconds(30);

    /** How long resolved MSR endpoints are cached */
    private static final Duration REGISTRY_CACHE_TTL = Duration.ofMinutes(10);

    private final McpSecurityService pki;
    private final SecomCertificateProvider certificateProvider;
    private final SecomSignatureProvider signatureProvider;

    /** SECOM wire JSON uses Jackson 2 (the SECOMLib models carry Jackson 2 annotations) */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** The setting naming the service registry, {@link #serviceRegistryUrl} */
    static final String REGISTRY_URL = "secomServiceRegistryUrl";

    @Setting(value = "secomServiceRegistryUrl", defaultValue = "",
            description = "URL of the SECOM service registry used to resolve peer endpoints.")
    volatile String serviceRegistryUrl;

    @Setting(value = "secomServiceRegistryInsecure", defaultValue = "false", type = Type.Boolean,
            description = "Skip TLS validation towards the SECOM service registry (test environments only).")
    volatile boolean serviceRegistryInsecure;

    @Setting(value = "secomAllowInsecureEndpoints", defaultValue = "false", type = Type.Boolean,
            description = "Allow SECOM deliveries to http:// peer endpoints and to peers on loopback, link-local "
                    + "or private addresses (test environments only).")
    volatile boolean allowInsecureEndpoints;

    private record CachedEndpoint(String endpoint, long resolvedAtMillis) {
    }

    private final Map<String, CachedEndpoint> endpointCache = new ConcurrentHashMap<>();

    /**
     * Reads whether outbound server certificates are revocation-checked - the same knob as the
     * inbound {@code MrnCertificateFilter} - at each https exchange, so a save applies to the next delivery
     */
    private final SettingsService settingsService;

    public SecomOutboundClient(McpSecurityService pki, NiordSecomProviders providers, SettingsService settingsService) {
        this.pki = pki;
        this.certificateProvider = providers;
        this.signatureProvider = providers;
        this.settingsService = settingsService;
    }

    /***************************************/
    /** Service registry                  **/
    /***************************************/

    /**
     * Resolves the SECOM service endpoint of the given MRN via the MCP Service Registry.
     * Only the instance registered under that very MRN is accepted, and its endpoint must pass
     * {@link #validatePeerEndpoint}. Resolutions are cached for a few minutes.
     */
    public String resolveEndpoint(String mrn) throws Exception {
        CachedEndpoint cached = endpointCache.get(mrn);
        if (cached != null && System.currentTimeMillis() - cached.resolvedAtMillis() < REGISTRY_CACHE_TTL.toMillis()) {
            return cached.endpoint();
        }

        if (serviceRegistryUrl == null || serviceRegistryUrl.isBlank()) {
            throw new IllegalStateException("The secomServiceRegistryUrl setting is not configured");
        }

        SearchParameters params = new SearchParameters();
        params.setInstanceId(mrn);
        EnvelopeSearchFilterObject envelope = new EnvelopeSearchFilterObject();
        envelope.setQuery(params);
        SearchFilterObject filter = new SearchFilterObject();
        filter.setEnvelope(envelope);
        filter.signEnvelope(certificateProvider, signatureProvider);

        List<ServiceInstanceObject> instances = post(
                serviceRegistryUrl + SearchServiceServiceInterface.SEARCH_SERVICE_INTERFACE_PATH,
                filter, SearchResult.class, serviceRegistryInsecure)
                .map(SearchResult::getEnvelope)
                .map(EnvelopeSearchResultObject::getServiceInstance)
                .orElse(List.of());

        // Only the instance registered under exactly this MRN may receive the node's deliveries -
        // a search can also return instances of other services, whose endpoints are not the
        // peer's. MRNs compare case-insensitively: the URN scheme and namespace ID are.
        String endpointUri = instances.stream()
                .filter(i -> mrn.equalsIgnoreCase(i.getInstanceId()))
                .map(ServiceInstanceObject::getEndpointUri)
                .filter(uri -> uri != null && !uri.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("MRN " + mrn + " not resolvable via service registry " + serviceRegistryUrl));

        // The registry returns the service base URL; SECOM paths are appended to it. Validated
        // before it is cached, so a refused endpoint is looked up (and refused) again next time.
        String endpoint = StringUtils.removeEnd(
                validatePeerEndpoint(endpointUri, allowInsecureEndpoints).toString(), "/");
        endpointCache.put(mrn, new CachedEndpoint(endpoint, System.currentTimeMillis()));
        log.info("Resolved SECOM endpoint for {}: {}", mrn, endpoint);
        return endpoint;
    }

    /** Drops the cached endpoint resolution of the given MRN (used when deliveries fail) */
    public void evictEndpoint(String mrn) {
        endpointCache.remove(mrn);
    }

    /**
     * A save of the registry URL drops the endpoints resolved before, on every node, so the next delivery asks the
     * registry now named instead of using the old registry's answer for the rest of {@link #REGISTRY_CACHE_TTL}
     *
     * @param event the setting whose field a save, here or on another node, changed
     */
    @EventListener
    void settingChanged(SettingChangedEvent event) {
        if (REGISTRY_URL.equals(event.key())) {
            endpointCache.clear();
        }
    }

    /***************************************/
    /** Peer endpoint checks              **/
    /***************************************/

    /**
     * Validates the syntax of a peer endpoint the service registry returned (or a URL composed
     * from it), before anything is cached or sent. Accepted is an absolute https URL with a host,
     * without user-info - which the client would send as credentials and the logs would record -
     * and without a query or fragment: the SECOM paths are appended to the endpoint as a string,
     * so a query or fragment would decide which path of the peer the request reaches.
     * <p>
     * Close to {@link NiordIntegrationUrlValidator#validateUrl} but stricter: peer deliveries
     * are https-only, so the MCP truststore and host-name verification always apply. Plain http
     * is accepted only when {@code allowInsecure} is set (the {@code secomAllowInsecureEndpoints}
     * setting, for test environments). The address check is {@link #requirePublicAddresses}.
     *
     * @param endpointUri   the endpoint to validate
     * @param allowInsecure whether plain http is allowed
     * @return the endpoint as a URI
     * @throws IllegalArgumentException if the endpoint is refused
     */
    static URI validatePeerEndpoint(String endpointUri, boolean allowInsecure) {
        URI uri;
        try {
            uri = new URI(StringUtils.trimToEmpty(endpointUri));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Refused SECOM peer endpoint " + endpointUri + ": not a valid URI");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        if (!https && !(http && allowInsecure)) {
            throw new IllegalArgumentException("Refused SECOM peer endpoint " + endpointUri
                    + ": an https URL is required");
        }
        if (uri.getRawUserInfo() != null) {
            // Deliberately does not echo the endpoint: the user-info may be a credential
            throw new IllegalArgumentException("Refused SECOM peer endpoint: it must not contain user info");
        }
        if (StringUtils.isBlank(uri.getHost())) {
            throw new IllegalArgumentException("Refused SECOM peer endpoint " + endpointUri + ": it has no valid host name");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Refused SECOM peer endpoint " + endpointUri
                    + ": it must not contain a query or fragment");
        }
        return uri;
    }

    /**
     * Refuses a delivery to a peer whose host resolves to a non-public address - loopback,
     * link-local (which includes cloud metadata services), private, unique-local, multicast or
     * reserved, as {@link NiordIntegrationUrlValidator#isNonPublicAddress} defines them - so a
     * registry entry cannot turn the signed push into a request into this node's own network.
     * Every address the host resolves to must be public. Skipped when {@code allowInsecure} is
     * set (the {@code secomAllowInsecureEndpoints} setting, for test environments).
     * <p>
     * The JDK HTTP client has no DNS hook, so it resolves the host again when it connects, and
     * a DNS answer that changes in between could still point the connection elsewhere. That
     * window is accepted: peer deliveries are https-only, so the connection completes only with
     * a server certificate that chains to the MCP truststore and matches the host name, and
     * redirects are never followed (the client's default {@code Redirect.NEVER}).
     * <p>
     * The lookup runs before the exchange, so it is bounded by the operating system's resolver
     * timeout, not by {@link #requestTimeout}.
     *
     * @param url           the peer URL about to be posted to
     * @param allowInsecure whether non-public addresses are allowed
     * @throws UnknownHostException  if the host does not resolve
     * @throws IllegalStateException if the host resolves to a non-public address
     */
    static void requirePublicAddresses(URI url, boolean allowInsecure) throws UnknownHostException {
        if (allowInsecure) {
            return;
        }
        for (InetAddress address : InetAddress.getAllByName(url.getHost())) {
            if (NiordIntegrationUrlValidator.isNonPublicAddress(address)) {
                throw new IllegalStateException("Refused SECOM peer endpoint " + url + ": host " + url.getHost()
                        + " resolves to the non-public address " + address.getHostAddress());
            }
        }
    }

    /***************************************/
    /** SECOM operations                  **/
    /***************************************/

    /**
     * Signs and uploads the given object to the node's SECOM endpoint. The envelope must be
     * fully populated except for metadata/signatures, which this method applies via the
     * SECOMLib helpers (same sequence as {@code SecomClient.upload}).
     */
    public void upload(String endpoint, UploadObject uploadObject) throws Exception {
        if (uploadObject.getEnvelope() != null) {
            uploadObject.getEnvelope()
                    .prepareMetadata(signatureProvider)
                    .signData(certificateProvider, signatureProvider)
                    .encryptData(null)
                    .compressData(null)
                    .encodeData();
        }
        uploadObject.signEnvelope(certificateProvider, signatureProvider);
        postToPeer(endpoint + UploadServiceInterface.UPLOAD_INTERFACE_PATH, uploadObject);
    }

    /** Signs and sends a subscription notification to the node's SECOM endpoint */
    public void subscriptionNotification(String endpoint, SubscriptionNotificationObject notification) throws Exception {
        notification.signEnvelope(certificateProvider, signatureProvider);
        postToPeer(endpoint + SubscriptionNotificationServiceInterface.SUBSCRIPTION_NOTIFICATION_INTERFACE_PATH, notification);
    }

    /***************************************/
    /** HTTP                              **/
    /***************************************/

    /**
     * POSTs to a peer: the composed URL is checked again (scheme, user-info, query, fragment)
     * and its host must resolve to public addresses only, then any non-2xx answer is a failure.
     * The sysadmin-configured registry URL does not go through here.
     */
    private void postToPeer(String url, Object body) throws Exception {
        URI uri = validatePeerEndpoint(url, allowInsecureEndpoints);
        requirePublicAddresses(uri, allowInsecureEndpoints);
        requireSuccess(url, doPost(uri, body, false));
    }

    private <T> Optional<T> post(String url, Object body, Class<T> responseType, boolean trustAll) throws Exception {
        HttpResponse<String> response = doPost(URI.create(url), body, trustAll);
        requireSuccess(url, response);
        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(mapper.readValue(responseBody, responseType));
    }

    private static void requireSuccess(String url, HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("SECOM POST " + url + " failed with HTTP " + response.statusCode()
                    + ": " + CertificateRenewalUtils.abbreviate(response.body()));
        }
    }

    /** {@return whether the server certificate of an https exchange is checked for revocation, as now saved} */
    private boolean revocationCheck() {
        return SecomConfiguration.booleanSetting(settingsService, SecomConfiguration.REVOCATION_CHECK);
    }

    /**
     * Sends one POST on a fresh client and waits for the complete response for at most
     * {@link #requestTimeout}. When the deadline passes, the exchange is cancelled and the
     * client shut down, and an {@link HttpTimeoutException} is thrown, which the dispatcher
     * records as a failed attempt with backoff like any other delivery failure.
     */
    private HttpResponse<String> doPost(URI url, Object body, boolean trustAll) throws Exception {
        String json = mapper.writeValueAsString(body);
        // By the scheme, case-insensitively: an "HTTPS:" URL must get the MCP client too
        HttpClient client = "https".equalsIgnoreCase(url.getScheme())
                ? pki.newHttpClient(trustAll, revocationCheck())
                : HttpClient.newBuilder().build();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(url)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            CompletableFuture<HttpResponse<String>> exchange =
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                return exchange.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                exchange.cancel(true);
                throw new HttpTimeoutException("SECOM POST " + url + " did not complete within "
                        + requestTimeout.toMillis() + " ms");
            } catch (InterruptedException e) {
                exchange.cancel(true);
                Thread.currentThread().interrupt();
                throw e;
            } catch (ExecutionException e) {
                // The same exceptions client.send() would have thrown (IOException etc.)
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw e;
            }
        } finally {
            // Each call has its own client: shutdownNow() aborts anything still in flight and
            // releases the client's threads right away, where close() would wait for them
            client.shutdownNow();
        }
    }

}
