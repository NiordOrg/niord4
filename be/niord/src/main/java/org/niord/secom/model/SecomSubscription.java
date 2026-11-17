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
import org.niord.core.model.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * A SECOM subscription created by a remote node via {@code POST /v2/subscription}.
 * <p>
 * The filter fields requested by the
 * subscriber — product version, data reference, WKT geometry, validity period — are
 * persisted and honored when datasets are pushed.
 */
@Entity
@NamedQueries({
        @NamedQuery(name = "SecomSubscription.findByUuid",
                query = "SELECT s FROM SecomSubscription s where s.uuid = :uuid"),
        @NamedQuery(name = "SecomSubscription.findByNodeMrn",
                query = "SELECT s FROM SecomSubscription s where s.node.mrn = :mrn order by s.created asc"),
        @NamedQuery(name = "SecomSubscription.findActive",
                query = "SELECT s FROM SecomSubscription s where "
                        + "(s.periodStart is null or s.periodStart <= :now) and "
                        + "(s.periodEnd is null or s.periodEnd >= :now)"),
        @NamedQuery(name = "SecomSubscription.findExpired",
                query = "SELECT s FROM SecomSubscription s where s.periodEnd < :now"),
        @NamedQuery(name = "SecomSubscription.findByDataReference",
                query = "SELECT s FROM SecomSubscription s where s.dataReference = :dataReference")
})
@SuppressWarnings("unused")
public class SecomSubscription extends BaseEntity<Integer> {

    /** The SECOM subscriptionIdentifier handed to the subscriber */
    @Column(nullable = false, unique = true)
    UUID uuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "node_id", nullable = false)
    SecomNode node;

    /** SECOM data product type, e.g. "S124" */
    @Column(nullable = false, length = 32)
    String dataProductType;

    @Column(length = 32)
    String productVersion;

    @Column(length = 32)
    String containerType;

    /** Optional filter: only this dataset (SECOM dataReference) */
    UUID dataReference;

    /** Optional geographic filter, parsed from the requested WKT */
    @Column(columnDefinition = "GEOMETRY")
    Geometry geometry;

    /** The original WKT as requested, kept for display */
    @Column(columnDefinition = "TEXT")
    String wkt;

    @Temporal(TemporalType.TIMESTAMP)
    Date periodStart;

    @Temporal(TemporalType.TIMESTAMP)
    Date periodEnd;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(nullable = false)
    Date created;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID();
        }
        if (created == null) {
            created = new Date();
        }
    }

    /** Returns if this subscription carries the same filters as the other one (same node assumed) */
    public boolean sameFilters(SecomSubscription o) {
        return Objects.equals(dataProductType, o.dataProductType)
                && Objects.equals(productVersion, o.productVersion)
                && Objects.equals(containerType, o.containerType)
                && Objects.equals(dataReference, o.dataReference)
                && Objects.equals(wkt, o.wkt)
                && Objects.equals(periodStart, o.periodStart)
                && Objects.equals(periodEnd, o.periodEnd);
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

    public SecomNode getNode() {
        return node;
    }

    public void setNode(SecomNode node) {
        this.node = node;
    }

    public String getDataProductType() {
        return dataProductType;
    }

    public void setDataProductType(String dataProductType) {
        this.dataProductType = dataProductType;
    }

    public String getProductVersion() {
        return productVersion;
    }

    public void setProductVersion(String productVersion) {
        this.productVersion = productVersion;
    }

    public String getContainerType() {
        return containerType;
    }

    public void setContainerType(String containerType) {
        this.containerType = containerType;
    }

    public UUID getDataReference() {
        return dataReference;
    }

    public void setDataReference(UUID dataReference) {
        this.dataReference = dataReference;
    }

    public Geometry getGeometry() {
        return geometry;
    }

    public void setGeometry(Geometry geometry) {
        this.geometry = geometry;
    }

    public String getWkt() {
        return wkt;
    }

    public void setWkt(String wkt) {
        this.wkt = wkt;
    }

    public Date getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(Date periodStart) {
        this.periodStart = periodStart;
    }

    public Date getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(Date periodEnd) {
        this.periodEnd = periodEnd;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }
}
