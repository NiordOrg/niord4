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

import org.locationtech.jts.geom.Geometry;
import org.niord.core.message.Message;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.util.Date;
import java.util.UUID;

/**
 * A persisted S-124 dataset: the identity, status and filter columns of the dataset generated
 * for a published navigational warning, pointing at the {@link S124DatasetVersion} currently
 * served.
 * <p>
 * One row per warning MRN. The SECOM {@code dataReference} (uuid) is derived deterministically
 * from the MRN, so it is stable across updates. The content itself is never overwritten: a
 * published update or cancellation appends a new, immutable version and moves the pointer,
 * so the bytes, catalogue entry and signature a consumer holds for any version stay on record.
 */
@Entity
@Table(indexes = {
        @Index(name = "s124_dataset_message", columnList = "message_id"),
        @Index(name = "s124_dataset_status", columnList = "status")
})
@NamedQueries({
        @NamedQuery(name = "S124Dataset.findByUuid",
                query = "SELECT d FROM S124Dataset d where d.uuid = :uuid"),
        @NamedQuery(name = "S124Dataset.findByMessageUid",
                query = "SELECT d FROM S124Dataset d where d.message.uid = :uid"),
        @NamedQuery(name = "S124Dataset.findAll",
                query = "SELECT d FROM S124Dataset d order by d.id asc"),
        @NamedQuery(name = "S124Dataset.findCancelledSince",
                query = "SELECT d FROM S124Dataset d where d.status = :status and d.cancelled >= :cutoff "
                        + " and d.currentVersion is not null order by d.cancelled desc")
})
@SuppressWarnings("unused")
public class S124Dataset extends BaseEntity<Integer> {

    public enum Status { ACTIVE, CANCELLED }

    /** The SECOM dataReference - deterministic UUIDv8 derived from the MRN */
    @Column(nullable = false, unique = true)
    UUID uuid;

    @Column(nullable = false, unique = true, length = 256)
    String mrn;

    /** The message this dataset was generated from */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    Message message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    Status status = Status.ACTIVE;

    /**
     * The version served over SECOM Get and announced in fileless cancellations: the latest
     * published. Set in the same transaction that writes the version, so a dataset row is never
     * left without one; the column is nullable only because the two rows reference each other.
     * Fetched eagerly: every reader of a dataset row - Get, GetSummary, the outbox, the sweep's
     * unchanged check - needs the version, and rows are handed out of their transaction (the
     * admin API, the tests), so a lazy pointer would only add a proxy to trip over.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "current_version_id")
    S124DatasetVersion currentVersion;

    /** Aggregate message geometry (WGS84), used for geometry filtering */
    @Column(columnDefinition = "GEOMETRY")
    Geometry geometry;

    @Temporal(TemporalType.TIMESTAMP)
    Date validFrom;

    @Temporal(TemporalType.TIMESTAMP)
    Date validTo;

    /** When the dataset flipped ACTIVE -> CANCELLED; drives the 365-day fileless-cancellation window */
    @Temporal(TemporalType.TIMESTAMP)
    Date cancelled;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(nullable = false)
    Date created;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(nullable = false)
    Date updated;

    @PrePersist
    protected void onCreate() {
        created = updated = new Date();
    }

    @PreUpdate
    protected void onUpdate() {
        updated = new Date();
    }

    /**
     * Returns whether the current version's dataset file is exactly the given bytes - the
     * idempotency check of the store: regenerating a published message yields the same file
     * unless the message changed, and only a changed file is worth a new version and a push.
     */
    public boolean hasCurrentContent(String gml) {
        return currentVersion != null && currentVersion.getGml().equals(gml);
    }

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public String getMrn() {
        return mrn;
    }

    public void setMrn(String mrn) {
        this.mrn = mrn;
    }

    public Message getMessage() {
        return message;
    }

    public void setMessage(Message message) {
        this.message = message;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public S124DatasetVersion getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(S124DatasetVersion currentVersion) {
        this.currentVersion = currentVersion;
    }

    public Geometry getGeometry() {
        return geometry;
    }

    public void setGeometry(Geometry geometry) {
        this.geometry = geometry;
    }

    public Date getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Date validFrom) {
        this.validFrom = validFrom;
    }

    public Date getValidTo() {
        return validTo;
    }

    public void setValidTo(Date validTo) {
        this.validTo = validTo;
    }

    public Date getCancelled() {
        return cancelled;
    }

    public void setCancelled(Date cancelled) {
        this.cancelled = cancelled;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public Date getUpdated() {
        return updated;
    }

    public void setUpdated(Date updated) {
        this.updated = updated;
    }
}
