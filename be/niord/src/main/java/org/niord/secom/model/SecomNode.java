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
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.util.Date;

/**
 * A remote SECOM/MCP peer (identified by its MRN) that has interacted with this node,
 * either as a subscriber or by acknowledging deliveries.
 */
@Entity
@NamedQueries({
        @NamedQuery(name = "SecomNode.findByMrn",
                query = "SELECT n FROM SecomNode n where n.mrn = :mrn")
})
@SuppressWarnings("unused")
public class SecomNode extends BaseEntity<Integer> {

    @Column(nullable = false, unique = true, length = 256)
    String mrn;

    /** Timestamp of the last authenticated SECOM interaction from this node - feeds /v2/ping */
    @Temporal(TemporalType.TIMESTAMP)
    Date lastInteraction;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(nullable = false)
    Date created;

    @PrePersist
    protected void onCreate() {
        if (created == null) {
            created = new Date();
        }
    }

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public String getMrn() {
        return mrn;
    }

    public void setMrn(String mrn) {
        this.mrn = mrn;
    }

    public Date getLastInteraction() {
        return lastInteraction;
    }

    public void setLastInteraction(Date lastInteraction) {
        this.lastInteraction = lastInteraction;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }
}
