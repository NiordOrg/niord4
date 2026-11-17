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
import org.grad.secomv2.core.models.EnvelopeSubscriptionNotificationObject;
import org.grad.secomv2.core.models.EnvelopeUploadObject;
import org.grad.secomv2.core.models.SubscriptionNotificationObject;
import org.grad.secomv2.core.models.UploadObject;
import org.grad.secomv2.core.models.enums.AckRequestEnum;
import org.grad.secomv2.core.models.enums.ContainerTypeEnum;
import org.grad.secomv2.core.models.enums.SECOM_DataProductType;
import org.grad.secomv2.core.models.enums.SubscriptionEventEnum;
import org.niord.core.NiordApp;
import org.niord.core.settings.annotation.Setting;
import org.niord.secom.SecomConfiguration;
import org.niord.secom.service.SecomOutboxService.DispatchTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.Optional;

import static org.niord.core.settings.Setting.Type;

/**
 * Scheduled dispatcher draining the SECOM outbox: resolves each due entry's peer endpoint
 * via the service registry, sends the signed upload / subscription notification, and applies
 * the success/backoff bookkeeping. Runs every minute (mirroring {@code ScheduledMailService});
 * failures never dead-letter silently — entries end as FAILED with the error recorded, and
 * can be retried from the admin API.
 * <p>
 * Dispatch works on detached {@link DispatchTask} snapshots, and each outcome is recorded in
 * its own transaction via {@link SecomOutboxService} — a slow or hanging remote node never
 * holds a database transaction open. Each entry is claimed in a committed transaction of its
 * own before it is sent, with a lease that keeps it from every other run (a second replica's)
 * until its outcome is recorded or the lease runs out.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class SecomOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SecomOutboxDispatcher.class);

    private final SecomOutboxService outboxService;
    private final SecomOutboundClient outboundClient;
    private final NiordApp app;

    @Setting(value = "secomOutboxMaxPerRun", defaultValue = "20", type = Type.Integer,
            description = "The max number of SECOM outbox entries dispatched per scheduler run (one run per minute)."
                    + " Read at start-up.")
    Integer maxPerRun;

    /** Overrides the SECOM callback endpoint sent with uploads; default = baseUri + /api/secom */
    @Setting(value = "secomCallbackEndpoint", defaultValue = "",
            description = "Overrides the SECOM callback endpoint sent with uploads (default: baseUri + /api/secom)."
                    + " Read at start-up.")
    String callbackEndpoint;

    public SecomOutboxDispatcher(SecomOutboxService outboxService, SecomOutboundClient outboundClient, NiordApp app) {
        this.outboxService = outboxService;
        this.outboundClient = outboundClient;
        this.app = app;
    }

    /** Called every minute ({@code niord.secom.outbox-dispatch-cron}; {@code -} disables) to dispatch due outbox entries */
    @Scheduled(cron = "${niord.secom.outbox-dispatch-cron:37 * * * * ?}")
    public void dispatchDueEntries() {
        try {
            dispatchAllDue();
        } catch (Exception e) {
            log.error("SECOM outbox dispatch run failed", e);
        }
    }

    /**
     * Dispatches the entries due when the run starts (bounded by secomOutboxMaxPerRun); returns the
     * count attempted. Each entry is claimed just before it is sent ({@link SecomOutboxService#claimNextDue}),
     * so two runs at once never send one entry twice, and a claim's lease only has to outlast one
     * delivery. An entry that falls due during the run waits for the next one.
     */
    public int dispatchAllDue() {
        Date runStart = new Date();
        int attempted = 0;
        while (attempted < maxPerRun) {
            Optional<DispatchTask> task = outboxService.claimNextDue(runStart);
            if (task.isEmpty()) {
                break;
            }
            dispatch(task.get());
            attempted++;
        }
        return attempted;
    }

    /** Sends a single outbox entry and records the outcome */
    private void dispatch(DispatchTask task) {
        try {
            String endpoint = outboundClient.resolveEndpoint(task.nodeMrn());
            switch (task.operation()) {
                case UPLOAD -> outboundClient.upload(endpoint, buildUploadObject(task));
                case SUBSCRIPTION_NOTIFICATION -> outboundClient.subscriptionNotification(endpoint, buildNotification(task));
            }
            outboxService.markSent(task.entryId());
            log.info("Dispatched SECOM {} (tx {}) to {}", task.operation(), task.transactionId(), task.nodeMrn());
        } catch (Exception e) {
            outboundClient.evictEndpoint(task.nodeMrn());
            outboxService.markFailedAttempt(task.entryId(), e.toString());
        }
    }

    /** Builds the SECOM upload object for an UPLOAD entry (signing happens in the client) */
    private UploadObject buildUploadObject(DispatchTask task) throws Exception {
        EnvelopeUploadObject envelope = new EnvelopeUploadObject();
        envelope.setData(task.payload());
        // The container the payload was snapshotted as at enqueue time; entries from before
        // exchange-set support carry no container type and were always bare datasets
        envelope.setContainerType(task.containerType() != null
                ? ContainerTypeEnum.valueOf(task.containerType())
                : ContainerTypeEnum.S100_DataSet);
        envelope.setDataProductType(SECOM_DataProductType.S124);
        envelope.setSubscriptionIdentifier(task.subscriptionUuid());
        envelope.setAckRequest(AckRequestEnum.DELIVERED_ACK_REQUESTED);
        // An upload requesting an ack must carry the callback endpoint for its delivery -
        // without it, conformant consumers reject the upload with 400 (design §6.1.4)
        envelope.setCallbackEndpoint(callbackUrl());
        envelope.setTransactionIdentifier(task.transactionId());

        UploadObject uploadObject = new UploadObject();
        uploadObject.setEnvelope(envelope);
        return uploadObject;
    }

    /** The SECOM endpoint of this node, where acknowledgements for uploads are expected */
    URL callbackUrl() throws Exception {
        String url = StringUtils.isNotBlank(callbackEndpoint)
                ? callbackEndpoint
                : StringUtils.removeEnd(app.getBaseUri(), "/") + SecomConfiguration.SECOM_BASE_PATH;
        return URI.create(url).toURL();
    }

    /** Builds the SECOM subscription notification object for a SUBSCRIPTION_NOTIFICATION entry */
    private SubscriptionNotificationObject buildNotification(DispatchTask task) {
        EnvelopeSubscriptionNotificationObject envelope = new EnvelopeSubscriptionNotificationObject();
        envelope.setSubscriptionIdentifier(task.subscriptionUuid());
        envelope.setEventEnum(SubscriptionEventEnum.valueOf(task.notificationEvent()));

        SubscriptionNotificationObject notification = new SubscriptionNotificationObject();
        notification.setEnvelope(envelope);
        return notification;
    }
}
