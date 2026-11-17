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

import org.grad.secomv2.core.exceptions.SecomNotFoundException;
import org.grad.secomv2.core.exceptions.SecomValidationException;
import org.grad.secomv2.core.models.enums.ContainerTypeEnum;
import org.grad.secomv2.core.models.enums.SubscriptionEventEnum;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.service.BaseService;
import org.niord.core.settings.annotation.Setting;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.SecomNode;
import org.niord.secom.model.SecomSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.niord.core.settings.Setting.Type;

/**
 * Manages the remote SECOM peers ({@link SecomNode}) and their subscriptions.
 * <p>
 * Subscription filters are persisted and honored
 * ({@code findMatching}), several subscriptions per node are allowed (the SECOM spec permits
 * it; identical duplicates return the existing subscription), and node interactions are
 * recorded on every authenticated request.
 * <p>
 * Provider-initiated removals mandated by the service design (v0.995 §6.5.4): expired
 * subscriptions are swept and removed on a schedule, and {@code dataReference} subscriptions
 * are terminated when the referenced dataset ceases — both with a SUBSCRIPTION_REMOVED
 * notification to the subscriber.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class SecomSubscriptionService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(SecomSubscriptionService.class);

    /**
     * Each subscription gets its own signed payload row per matching dataset version, so the
     * number of subscriptions one node may hold bounds how much outbox work (and storage) a
     * single peer can cause.
     */
    @Setting(value = "secomMaxSubscriptionsPerNode", defaultValue = "50", type = Type.Integer,
            description = "The max number of SECOM subscriptions one remote node may hold; further "
                    + "(non-identical) subscription requests are rejected. Read at start-up.")
    Integer maxSubscriptionsPerNode;

    private final SecomOutboxService outboxService;

    public SecomSubscriptionService(SecomOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    /***************************************/
    /** Nodes                             **/
    /***************************************/

    /** Returns the node with the given MRN, or null if unknown */
    public SecomNode findNodeByMrn(String mrn) {
        return getSingleOrNull(em.createNamedQuery("SecomNode.findByMrn", SecomNode.class)
                .setParameter("mrn", mrn)
                .getResultList());
    }

    /** Returns the node with the given MRN, creating it if unknown */
    @Transactional
    public SecomNode findOrCreateNode(String mrn) {
        SecomNode node = findNodeByMrn(mrn);
        if (node == null) {
            node = new SecomNode();
            node.setMrn(mrn);
            node = saveEntity(node);
            log.info("Registered new SECOM node {}", mrn);
        }
        return node;
    }

    /**
     * Records an authenticated SECOM interaction from the given MRN (feeds /v2/ping).
     * Runs in its own transaction so a failure never affects the actual request.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchNode(String mrn) {
        SecomNode node = findOrCreateNode(mrn);
        node.setLastInteraction(new Date());
        saveEntity(node);
    }

    /***************************************/
    /** Subscriptions                     **/
    /***************************************/

    /** Returns the subscription with the given SECOM subscriptionIdentifier, or null */
    public SecomSubscription findByUuid(UUID uuid) {
        return getSingleOrNull(em.createNamedQuery("SecomSubscription.findByUuid", SecomSubscription.class)
                .setParameter("uuid", uuid)
                .getResultList());
    }

    /**
     * Creates a subscription for the given node, unless an identical one (same filters)
     * already exists, in which case the existing subscription is returned.
     *
     * @param mrn      the MRN of the subscribing node
     * @param template a detached subscription carrying the requested filter fields
     * @return the persisted subscription
     */
    @Transactional
    public SecomSubscription subscribe(String mrn, SecomSubscription template) {
        SecomNode node = findOrCreateNode(mrn);

        // An unspecified container means the design-default exchange set. Persist the
        // effective value, so a later subscription spelling the default explicitly dedupes
        // against it instead of creating a second subscription pushing identical payloads.
        if (template.getContainerType() == null) {
            template.setContainerType(ContainerTypeEnum.S100_ExchangeSet.name());
        }

        List<SecomSubscription> existing = em.createNamedQuery("SecomSubscription.findByNodeMrn", SecomSubscription.class)
                .setParameter("mrn", mrn)
                .getResultList();
        for (SecomSubscription s : existing) {
            if (s.sameFilters(template)) {
                log.info("Returning existing identical subscription {} for {}", s.getUuid(), mrn);
                // The design (v0.995 §7.3.1) confirms every subscription request with a
                // SUBSCRIPTION_CREATED notification - also when the request deduplicates
                // onto an existing subscription (e.g. a consumer re-subscribing after a
                // restart must still receive the confirmation callback it waits for)
                outboxService.enqueueNotification(s, SubscriptionEventEnum.SUBSCRIPTION_CREATED);
                return s;
            }
        }

        // Checked after the dedupe, so a node at the cap can still re-confirm what it holds
        if (maxSubscriptionsPerNode != null && existing.size() >= maxSubscriptionsPerNode) {
            log.warn("Rejected a subscription request from {}: it already holds {} subscriptions (secomMaxSubscriptionsPerNode)",
                    mrn, existing.size());
            throw new SecomValidationException("The maximum number of subscriptions per node ("
                    + maxSubscriptionsPerNode + ") has been reached; remove a subscription before adding another");
        }

        template.setNode(node);
        SecomSubscription subscription = saveEntity(template);
        log.info("Created subscription {} for {}", subscription.getUuid(), mrn);

        // Notify the subscriber asynchronously via the outbox (never synchronously inside
        // the subscribe transaction)
        outboxService.enqueueNotification(subscription, SubscriptionEventEnum.SUBSCRIPTION_CREATED);
        return subscription;
    }

    /**
     * Removes the subscription with the given identifier. The requesting node must own it -
     * subscriptions owned by other nodes are reported as not found.
     *
     * @return the removed subscription
     */
    @Transactional
    public SecomSubscription unsubscribe(String mrn, UUID uuid) {
        SecomSubscription subscription = findByUuid(uuid);
        if (subscription == null || !subscription.getNode().getMrn().equals(mrn)) {
            if (subscription != null) {
                log.warn("Node {} attempted to delete subscription {} owned by {}", mrn, uuid, subscription.getNode().getMrn());
            }
            throw new SecomNotFoundException("Unknown subscription with UUID " + uuid);
        }
        outboxService.enqueueNotification(subscription, SubscriptionEventEnum.SUBSCRIPTION_REMOVED);
        remove(subscription);
        log.info("Removed subscription {} for {}", uuid, mrn);
        return subscription;
    }

    /** Removes a subscription without an ownership check (admin operation) */
    @Transactional
    public SecomSubscription adminRemove(UUID uuid) {
        SecomSubscription subscription = findByUuid(uuid);
        if (subscription == null) {
            throw new IllegalArgumentException("Unknown subscription " + uuid);
        }
        outboxService.enqueueNotification(subscription, SubscriptionEventEnum.SUBSCRIPTION_REMOVED);
        remove(subscription);
        log.info("Admin removed subscription {}", uuid);
        return subscription;
    }

    /**
     * Scheduled provider-side removal of subscriptions whose subscriptionPeriodEnd has been
     * reached — the design (§6.5.4) requires them removed, not just skipped, and the
     * subscriber notified via its Subscription Notification interface (§7.3.3).
     */
    @Scheduled(cron = "${niord.secom.subscription-expiry-cron:23 1/5 * * * ?}")
    @Transactional
    public void removeExpiredSubscriptions() {
        List<SecomSubscription> expired = em.createNamedQuery("SecomSubscription.findExpired", SecomSubscription.class)
                .setParameter("now", new Date())
                .getResultList();
        for (SecomSubscription subscription : expired) {
            outboxService.enqueueNotification(subscription, SubscriptionEventEnum.SUBSCRIPTION_REMOVED);
            remove(subscription);
            log.info("Removed expired subscription {} for {}", subscription.getUuid(), subscription.getNode().getMrn());
        }
    }

    /**
     * Terminates all subscriptions pinned to the given dataReference. Called when the
     * referenced dataset ceases (is cancelled) — per the design (§6.5.4) such subscriptions
     * terminate automatically. Callers must enqueue the final dataset push first; outbox
     * entries are dispatched independently of the subscription row.
     */
    @Transactional
    public void removeForDataReference(UUID dataReference) {
        List<SecomSubscription> subscriptions = em.createNamedQuery("SecomSubscription.findByDataReference", SecomSubscription.class)
                .setParameter("dataReference", dataReference)
                .getResultList();
        for (SecomSubscription subscription : subscriptions) {
            outboxService.enqueueNotification(subscription, SubscriptionEventEnum.SUBSCRIPTION_REMOVED);
            remove(subscription);
            log.info("Removed subscription {} for {} - its dataReference {} ceased",
                    subscription.getUuid(), subscription.getNode().getMrn(), dataReference);
        }
    }

    /**
     * Returns the currently active subscriptions matching the given dataset: the
     * subscription period must cover now, and the product type, container type, product
     * version, dataReference and geometry filters (where present) must all match.
     * <p>
     * Geometry matching follows the service spec: the subscription area is intersected with
     * the dataset's <i>message vicinity</i> (§6.3.1: the message area unioned with the areas
     * of all referenced messages), and per the design (§6.5.4) the geometry criterion is
     * ignored for datasets that are not geotagged.
     *
     * @param dataset  the dataset to match
     * @param vicinity the dataset's vicinity geometry; null when the dataset is not geotagged
     */
    public List<SecomSubscription> findMatching(S124Dataset dataset, Geometry vicinity) {
        return em.createNamedQuery("SecomSubscription.findActive", SecomSubscription.class)
                .setParameter("now", new Date())
                .getResultList().stream()
                .filter(s -> "S124".equals(s.getDataProductType()))
                // Both containers can be pushed (the payload follows the subscription in
                // enqueueUpload); never push to a subscription for a container we cannot serve
                .filter(s -> s.getContainerType() == null
                        || "S100_ExchangeSet".equals(s.getContainerType())
                        || "S100_DataSet".equals(s.getContainerType()))
                .filter(s -> s.getProductVersion() == null
                        || s.getProductVersion().equals(S124DatasetService.S124_PRODUCT_VERSION))
                .filter(s -> s.getDataReference() == null || s.getDataReference().equals(dataset.getUuid()))
                .filter(s -> s.getGeometry() == null
                        || vicinity == null
                        || s.getGeometry().intersects(vicinity))
                .toList();
    }
}
