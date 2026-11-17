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
package org.niord.secom.model;

import org.niord.core.model.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.util.Date;
import java.util.UUID;

/**
 * Durable SECOM outbox: one row per operation (dataset upload or subscription notification)
 * to one remote node, dispatched by the scheduled {@code SecomOutboxDispatcher} with
 * retry/backoff, and updated by the {@code POST /v2/acknowledgement} endpoint.
 * <p>
 * The row doubles as the SECOM transaction record: its {@code transactionId} is the SECOM
 * {@code transactionIdentifier} of the upload envelope, so acknowledgements resolve directly
 * to it (modeled on Niord's {@code ScheduledMail} queue).
 */
@Entity
@Table(indexes = {
        @Index(name = "secom_outbox_status", columnList = "status,nextAttemptAt"),
        @Index(name = "secom_outbox_node", columnList = "node_id")
})
@NamedQueries({
        @NamedQuery(name = "SecomOutboxEntry.findDue",
                query = "SELECT o FROM SecomOutboxEntry o where o.status = 'PENDING' and o.nextAttemptAt <= :date "
                        + " order by o.id asc"),
        @NamedQuery(name = "SecomOutboxEntry.findByTransactionId",
                query = "SELECT o FROM SecomOutboxEntry o where o.transactionId = :transactionId"),
        @NamedQuery(name = "SecomOutboxEntry.findByStatus",
                query = "SELECT o FROM SecomOutboxEntry o where o.status = :status order by o.id desc"),
        @NamedQuery(name = "SecomOutboxEntry.findRecent",
                query = "SELECT o FROM SecomOutboxEntry o order by o.id desc")
})
@SuppressWarnings("unused")
public class SecomOutboxEntry extends BaseEntity<Integer> {

    public enum Operation { UPLOAD, SUBSCRIPTION_NOTIFICATION }

    public enum Status { PENDING, SENT, ACKED, OPENED, FAILED, CANCELLED }

    /** The SECOM transactionIdentifier of the outbound envelope */
    @Column(nullable = false, unique = true)
    UUID transactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "node_id", nullable = false)
    SecomNode node;

    /** The subscription this delivery belongs to (kept loose - the subscription may be deleted) */
    UUID subscriptionUuid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    Operation operation;

    /** For SUBSCRIPTION_NOTIFICATION entries: the SubscriptionEventEnum name */
    @Column(length = 64)
    String notificationEvent;

    /** For UPLOAD entries: the dataset being pushed */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dataset_id")
    S124Dataset dataset;

    /** For UPLOAD entries: snapshot of the exact payload bytes to push (audit trail) */
    @Column
    byte[] payload;

    /**
     * For UPLOAD entries: the SECOM container type of the snapshotted payload
     * ({@code S100_ExchangeSet} or {@code S100_DataSet}), decided from the subscription
     * when the entry is enqueued and echoed in the upload envelope.
     */
    @Column(length = 32)
    String containerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    Status status = Status.PENDING;

    @Column(nullable = false)
    int attempts;

    @Temporal(TemporalType.TIMESTAMP)
    Date nextAttemptAt;

    @Column(columnDefinition = "TEXT")
    String lastError;

    @Temporal(TemporalType.TIMESTAMP)
    Date sentAt;

    @Temporal(TemporalType.TIMESTAMP)
    Date ackedAt;

    @Temporal(TemporalType.TIMESTAMP)
    Date openedAt;

    @Column(length = 64)
    String nackType;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(nullable = false)
    Date created;

    @PrePersist
    protected void onCreate() {
        if (transactionId == null) {
            transactionId = UUID.randomUUID();
        }
        if (created == null) {
            created = new Date();
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = created;
        }
    }

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public SecomNode getNode() {
        return node;
    }

    public void setNode(SecomNode node) {
        this.node = node;
    }

    public UUID getSubscriptionUuid() {
        return subscriptionUuid;
    }

    public void setSubscriptionUuid(UUID subscriptionUuid) {
        this.subscriptionUuid = subscriptionUuid;
    }

    public Operation getOperation() {
        return operation;
    }

    public void setOperation(Operation operation) {
        this.operation = operation;
    }

    public String getNotificationEvent() {
        return notificationEvent;
    }

    public void setNotificationEvent(String notificationEvent) {
        this.notificationEvent = notificationEvent;
    }

    public S124Dataset getDataset() {
        return dataset;
    }

    public void setDataset(S124Dataset dataset) {
        this.dataset = dataset;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public String getContainerType() {
        return containerType;
    }

    public void setContainerType(String containerType) {
        this.containerType = containerType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Date getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Date nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Date getSentAt() {
        return sentAt;
    }

    public void setSentAt(Date sentAt) {
        this.sentAt = sentAt;
    }

    public Date getAckedAt() {
        return ackedAt;
    }

    public void setAckedAt(Date ackedAt) {
        this.ackedAt = ackedAt;
    }

    public Date getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Date openedAt) {
        this.openedAt = openedAt;
    }

    public String getNackType() {
        return nackType;
    }

    public void setNackType(String nackType) {
        this.nackType = nackType;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }
}
