/*
 * Copyright 2017 Danish Maritime Authority.
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

import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.niord.core.area.AreaService;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.ChartService;
import org.niord.core.geojson.FeatureService;
import org.niord.core.integration.vo.MessageSeriesMappingVo;
import org.niord.core.integration.vo.NiordIntegrationVo;
import org.niord.core.message.*;
import org.niord.core.service.BaseService;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.util.WebUtils;
import org.niord.model.message.MessageVo;
import org.niord.model.message.Status;
import org.slf4j.Logger;

import static org.niord.core.settings.Setting.Type;

import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handles the actual Niord integration execution
 */
@Service
@SuppressWarnings("unused")
public class NiordIntegrationExecutionService extends BaseService {

    public static final int TIMEOUT = 5; // 5 seconds

    private Logger log = org.slf4j.LoggerFactory.getLogger(NiordIntegrationExecutionService.class);

    private final MessageSeriesService messageSeriesService;

    private final MessageService messageService;

    private final MessageSearchService messageSearchService;

    private final AreaService areaService;

    private final CategoryService categoryService;

    private final ChartService chartService;

    private final FeatureService featureService;

    private final MessageBaseDataResolver baseDataResolver;

    /**
     * Whether the importer may fetch from loopback, private and other non-public addresses. Off by
     * default, so that the URL of an integration point cannot be used to reach into the network the
     * server runs in. Switch it on where the remote Niord server genuinely is on a private network.
     * Consumed once at bean creation, like the other {@code @Setting} fields - restart to apply.
     */
    @Setting(value = "niordIntegrationAllowPrivateHosts", defaultValue = "false", type = Type.Boolean,
            description = "Allow the Niord integration import to fetch from private/loopback addresses."
                    + " Read at start-up.")
    boolean allowPrivateHosts;

    NiordIntegrationExecutionService(MessageSeriesService messageSeriesService, MessageService messageService, MessageSearchService messageSearchService, AreaService areaService, CategoryService categoryService, ChartService chartService, FeatureService featureService) {
        this.messageSeriesService = messageSeriesService;
        this.messageService = messageService;
        this.messageSearchService = messageSearchService;
        this.areaService = areaService;
        this.categoryService = categoryService;
        this.chartService = chartService;
        this.featureService = featureService;
        this.baseDataResolver = new MessageBaseDataResolver(areaService, categoryService, chartService);
    }

    /**
     * Processes the given Niord Integration
     */
    // PORT: was jakarta.ejb.@Asynchronous (§12). Quarkus CDI does NOT honour
    // @Asynchronous without the EJB extension, so under Quarkus this fire-and-forget,
    // void-returning method actually ran SYNCHRONOUSLY on the caller's thread. Using
    // Spring's @Async here would change that observable behaviour: the import would run
    // on a separate thread and the caller (the @Scheduled NiordIntegrationService and the
    // REST endpoint) would return before any message was fetched/reconciled. To keep the
    // observable result identical to the original deployment, the call remains synchronous
    // (no @Async). The contract is unchanged: void return, all work done before returning.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processNiordIntegration(NiordIntegrationVo integration) {

        // Check that proper message series have been defined
        if (!integration.isActive() || integration.getMessageSeriesMappings().isEmpty()) {
            return;
        }

        try {
            // Fetch message for all source message series
            List<MessageVo> messages = fetchMessages(integration);

            Set<String> targetSeriesIds = integration.getMessageSeriesMappings().stream()
                    .map(MessageSeriesMappingVo::getTargetSeriesId)
                    .collect(Collectors.toSet());

            // Handle target message series one by one
            for (String targetSeriesId : targetSeriesIds) {

                MessageSeries targetSeries = messageSeriesService.findBySeriesId(targetSeriesId);
                if (targetSeries == null) {
                    continue;
                }

                // Get the source message series IDs that are mapped to the target message series
                // Typically, there will only be one, but there may actually be more
                Set<String> sourceSeriesIds = integration.getMessageSeriesMappings().stream()
                        .filter(ms -> targetSeriesId.equals(ms.getTargetSeriesId()))
                        .map(MessageSeriesMappingVo::getSourceSeriesId)
                        .collect(Collectors.toSet());

                // Get all source message whose message series ids should be mapped to the target message series
                List<MessageVo> importMessages = messages.stream()
                        .filter(m -> sourceSeriesIds.contains(m.getMessageSeries().getSeriesId()))
                        .collect(Collectors.toList());
                Map<String, MessageVo> importMessageMap = importMessages.stream()
                        .collect(Collectors.toMap(MessageVo::getId, Function.identity()));

                // Get our own messages for the target message series
                List<Message> ownMessages = fetchOwnMessages(targetSeriesId);

                // An empty answer for a series that holds imported warnings is not read as a withdrawal of all of
                // them: the source series renamed or removed there, or a server with no data yet behind the URL,
                // answers the same. But the last warning of a series withdrawn there answers the same too, so
                // each imported warning is asked for by its id: one the other server answers as no longer published
                // is cancelled here, as an answer that leaves out only some warnings would cancel it; one it cannot
                // answer for is kept, and the run says so
                if (importMessages.isEmpty() && !ownMessages.isEmpty()) {
                    List<Message> withdrawn = withdrawnAtTheSource(integration, ownMessages);
                    for (Message msg : withdrawn) {
                        messageService.updateStatus(msg.getUid(), Status.CANCELLED);
                    }
                    int kept = ownMessages.size() - withdrawn.size();
                    if (kept > 0) {
                        log.warn("Integration " + integration.getId() + ": the other server served no message of "
                                + sourceSeriesIds + "; keeping the " + kept + " imported warning(s) of "
                                + targetSeriesId + " it could not answer for, rather than cancelling them");
                    }
                    continue;
                }

                // Two own copies of one source warning (no unique index on the legacy id) are reported, and the copy
                // with the lowest id is the one kept in step; the series still reconciles. The other copy is
                // not updated; a system administrator cancels it by hand
                Map<String, Message> ownMessageMap = ownMessages.stream()
                        .collect(Collectors.toMap(Message::getLegacyId, Function.identity(), (a, b) -> {
                            Message kept = a.getId() <= b.getId() ? a : b;
                            Message extra = kept == a ? b : a;
                            log.warn("Integration " + integration.getId() + ": messages " + kept.getUid() + " and "
                                    + extra.getUid() + " of " + targetSeriesId + " both carry legacy id "
                                    + kept.getLegacyId() + "; only " + kept.getUid()
                                    + " is kept in step with the other server, cancel the other by hand");
                            return kept;
                        }));

                // Determine which of our current messages to cancel and which of the fetched messages to import
                List<Message> cancelMessages = ownMessages.stream()
                        .filter(m -> !importMessageMap.containsKey(m.getLegacyId()))
                        .collect(Collectors.toList());
                List<MessageVo> createMessages = importMessages.stream()
                        .filter(m -> !ownMessageMap.containsKey(m.getId()))
                        .collect(Collectors.toList());
                List<MessageVo> updateMessages = importMessages.stream()
                        .filter(m -> ownMessageMap.containsKey(m.getId()))
                        .filter(m -> m.getUpdated().after(ownMessageMap.get(m.getId()).getUpdated()))
                        .collect(Collectors.toList());

                // Cancel own message not found in the fetched message list
                for (Message msg : cancelMessages) {
                    messageService.updateStatus(msg.getUid(), Status.CANCELLED);
                }

                // Create fetched messages not yet imported
                for (MessageVo msg : createMessages) {
                    importOrSkip(msg, targetSeries, integration);
                }

                // Update existing messages that have been updated after import. Import the new
                // version FIRST and only cancel our old copy once the re-import has succeeded - so a
                // failing re-import (e.g. a malformed source message) leaves the existing published
                // Notice-to-Mariners intact instead of cancelling it with no replacement.
                for (MessageVo msg : updateMessages) {
                    Message message = ownMessageMap.get(msg.getId());
                    if (importOrSkip(msg, targetSeries, integration)) {
                        messageService.updateStatus(message.getUid(), Status.CANCELLED);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error fetching messages from integration " + integration.getId(), e);
        }
    }

    /**
     * Fetches published messages from this Niord server for the given message series.
     * The messages should also have a defined legacyId to partake in the process.
     * A failed look-up stops the run rather than read as "no own copies", which would import every warning again.
     *
     * @param seriesId the messages series
     * @return published messages from this Niord server for the given message series
     */
    List<Message> fetchOwnMessages(String seriesId) throws Exception {
        MessageSearchParams params = new MessageSearchParams()
                .seriesIds(Collections.singleton(seriesId))
                .statuses(Status.PUBLISHED);
        params.maxSize(10000);

        return messageSearchService.searchOrFail(params)
                .getData().stream()
                .filter(m -> StringUtils.isNotBlank(m.getLegacyId()))
                .collect(Collectors.toList());
    }

    /**
     * Imports the message, or skips it when this Niord refuses its content as it builds the message (a position off
     * the globe: a latitude beyond 90, a coordinate that is not a number, a span over 360 degrees, which an older
     * server without the check may serve). The refusal comes before any transactional call, so the run's transaction
     * is not marked for rollback: one such warning is skipped, with a WARN that names it, and the rest of the run
     * goes on. Any other failure still ends the run, as before, and so does a refusal that has marked the run's
     * transaction for rollback (the rest of the run could not commit).
     *
     * @return whether the message was imported
     */
    private boolean importOrSkip(MessageVo msg, MessageSeries messageSeries, NiordIntegrationVo integration)
            throws Exception {
        try {
            importMessage(msg, messageSeries, integration);
            return true;
        } catch (IllegalArgumentException e) {
            if (runMarkedForRollback()) {
                throw e;
            }
            log.warn("Integration " + integration.getId() + ": message " + msg.getId() + " is skipped, this Niord"
                    + " refuses its content (" + e.getMessage() + "); the rest of the run goes on");
            return false;
        }
    }

    /** Whether the run's transaction is marked for rollback (false when there is none) */
    private static boolean runMarkedForRollback() {
        try {
            return TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();
        } catch (NoTransactionException e) {
            return false;
        }
    }

    /** Imports the message and assigns the given message series */
    protected void importMessage(MessageVo msg, MessageSeries messageSeries, NiordIntegrationVo integration) throws Exception {
        try {
            Message message = new Message(msg);
            message.setMessageSeries(messageSeries);

            message.setVersion(0);
            message.setUid(null);
            message.setLegacyId(msg.getId());

            if (integration.isAssignNewUids()) {
                message.assignNewUid();
            }

            // Ensure that the main type and type adheres to the message series
            message.setMainType(messageSeries.getMainType());
            if (!messageSeries.getTypes().isEmpty() && !messageSeries.getTypes().contains(message.getType())) {
                message.setType(messageSeries.getTypes().iterator().next());
            } else if (message.getType().getMainType() != message.getMainType()) {
                message.setType(message.getMainType().anyType());
            }

            if (message.getPublishDateFrom() == null) {
                // Published messages should have a published from-date
                message.setPublishDateFrom(new Date());
            }
            // The year of the publish date in the zone of the series' domain; the save keeps it
            message.checkUpdateYear(messageSeriesService.seriesZone(messageSeries));

            // Make sure areas, categories and charts are resolved and/or created. One this Niord does not have (and
            // does not create) is left out of the message, and the log says so
            List<String> leftOut = baseDataResolver.resolve(message, integration.isCreateBaseData());
            for (String reference : leftOut) {
                log.warn("Integration " + integration.getId() + ": message " + msg.getId() + ": the " + reference
                        + " is not in this Niord and is left out");
            }

            // Reset all geometry IDs
            message.getParts().stream()
                    .filter(p -> p.getGeometry() != null)
                    .forEach(p -> {
                        p.getGeometry().setUid(null);
                        featureService.assignNewFeatureUids(p.getGeometry());
                    });

            // Reset all attachment IDs
            message.getAttachments().forEach(att -> att.setId(null));

            messageService.createMessage(message);

            log.info("Imported message " + message
                    + (leftOut.isEmpty() ? "" : " without " + leftOut.size() + " reference(s)"));

        } catch (Exception e) {
            log.error("Failed importing message: " + e, e);
            throw e;
        }
    }

    /**
     * The own copies whose source warning the other server answers as no longer published (cancelled or expired
     * there), asked for one by one by their legacy ids through the public single-message read. A copy the other server
     * cannot answer for (no such id there, any other answer) is not among them. When the other server cannot be
     * reached at all, the rest are not asked for either: they are kept.
     */
    List<Message> withdrawnAtTheSource(NiordIntegrationVo integration, List<Message> ownMessages) {
        Map<String, Status> statuses = new HashMap<>();
        List<Message> withdrawn = new ArrayList<>();
        for (Message own : ownMessages) {
            String legacyId = own.getLegacyId();
            if (!statuses.containsKey(legacyId)) {
                try {
                    statuses.put(legacyId, fetchSourceStatus(integration, legacyId));
                } catch (Exception e) {
                    log.warn("Integration " + integration.getId() + ": cannot ask the other server for message "
                            + legacyId + ": " + e.getMessage());
                    break;
                }
            }
            Status status = statuses.get(legacyId);
            if (status != null && status != Status.PUBLISHED) {
                log.info("Integration " + integration.getId() + ": message " + legacyId + " is " + status
                        + " on the other server; cancelling its copy " + own.getUid());
                withdrawn.add(own);
            }
        }
        return withdrawn;
    }

    /**
     * The status the other server gives the message with the given id ({@code /rest/public/v1/message/<id>}, which
     * answers a published, cancelled or expired message), or null when it answers for none (404, or any other answer
     * that is not that message).
     *
     * @throws Exception when the other server cannot be reached, or the URL may not be fetched from
     */
    Status fetchSourceStatus(NiordIntegrationVo integration, String messageId) throws Exception {
        NiordIntegrationUrlValidator.validateUrl(integration.getUrl());
        String url = integration.getUrl() + "/rest/public/v1/message/" + WebUtils.encodeURIComponent(messageId);
        try (CloseableHttpClient client = newHttpClient();
             CloseableHttpResponse response = client.execute(new HttpGet(url))) {
            HttpEntity entity = response.getEntity();
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status > 299 || entity == null) {
                if (entity != null) {
                    entity.getContent().close();
                }
                return null;
            }
            try (InputStream is = entity.getContent()) {
                MessageVo message = JsonMapper.builderWithJackson2Defaults().build().readValue(is, MessageVo.class);
                return message != null && messageId.equals(message.getId()) ? message.getStatus() : null;
            } catch (tools.jackson.core.JacksonException e) {
                log.debug("Integration " + integration.getId() + ": the answer for message " + messageId
                        + " is not a message: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * An HTTP client for the other server. The default TLS setup of HttpClients.custom() - the JVM trust store and
     * the default host name verifier - is deliberate: the remote server is admin-supplied and the messages it returns
     * are published locally, so a trusted certificate matching the host name is the only thing tying the import to
     * the intended Niord server. If the remote certificate does not chain to a public root, add its CA to the JVM
     * trust store. The DNS resolver additionally keeps the fetch - and any redirect it follows - away from
     * non-public addresses.
     */
    private CloseableHttpClient newHttpClient() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(TIMEOUT * 1000)
                .setConnectionRequestTimeout(TIMEOUT * 1000)
                .setSocketTimeout(TIMEOUT * 1000).build();
        return HttpClients.custom()
                .setDefaultRequestConfig(config)
                .setDnsResolver(NiordIntegrationUrlValidator.dnsResolver(allowPrivateHosts))
                .build();
    }

    /**
     * Fetches the messages defined by the Niord integration point
     * @param integration the Niord integration point to fetch message from
     * @return the messages fetched from the Niord integration point
     */
    public List<MessageVo> fetchMessages(NiordIntegrationVo integration) throws Exception {

        long t0 = System.currentTimeMillis();

        // Only fetch from a URL the importer is allowed to fetch from
        NiordIntegrationUrlValidator.validateUrl(integration.getUrl());

        // Construct the URL to fetch messages from
        String params = integration.getMessageSeriesMappings().stream()
                .map(m -> "messageSeries=" + WebUtils.encodeURIComponent(m.getSourceSeriesId()))
                .collect(Collectors.joining("&"));

        // The endpoint path is appended as a raw string, so the base URL has to be one it can be
        // appended to: validateUrl() above rejects a query or fragment, which would otherwise swallow
        // this path (see its javadoc). A trailing slash needs no handling - HttpClient normalizes the
        // resulting "//rest/..." before it reaches the wire.
        String url = integration.getUrl() + "/rest/public/v1/messages?" + params;

        // The TLS and DNS set-up: see newHttpClient()
        CloseableHttpClient client = newHttpClient();

        try (CloseableHttpResponse response = client.execute(new HttpGet(url))) {
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status > 299) {
                try {
                    response.getEntity().getContent().close();
                } catch (Exception ignored) {
                }
                throw new Exception("Unable to execute request " + url + ", status = " + status);
            }

            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw new Exception("No response received from URL " + url);
            }

            try (InputStream is = entity.getContent()) {
                List<MessageVo> result = JsonMapper.builderWithJackson2Defaults().build().readValue(is, new TypeReference<List<MessageVo>>(){});
                log.debug("Fetching " + result.size() + " messages from URL " + url + " in " +
                        (System.currentTimeMillis() - t0) + " ms");
                return result;
            }
        }
    }

}
