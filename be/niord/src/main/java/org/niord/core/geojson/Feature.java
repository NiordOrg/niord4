/*
 * Copyright 2016 Danish Maritime Authority.
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

package org.niord.core.geojson;

import org.locationtech.jts.geom.Geometry;
import org.niord.core.db.JpaPropertiesAttributeConverter;
import org.niord.core.model.BaseEntity;
import org.niord.core.model.IndexedEntity;
import org.niord.model.geojson.FeatureVo;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a GeoJson feature entity
 * <p>
 * TODO
 * Create index using "alter table Feature add spatial index feature_index (geometry);"
 */
@Entity
@Table(indexes = {
        @Index(name = "feature_uid", columnList="uid", unique = true)
})
@NamedQueries({
        @NamedQuery(name  = "Feature.findByUid",
                query = "select f from Feature f where f.uid = :uid"),
        @NamedQuery(name  = "Feature.hits",
                query = "select f from Feature f where within(:pt, f.geometry) = true")
})
@SuppressWarnings("unused")
public class Feature extends BaseEntity<Integer> implements IndexedEntity {
    public final static int WGS84_SRID = 4326;

    @Column(unique = true)
    String uid;

    @ManyToOne
    @NotNull
    FeatureCollection featureCollection;

    // The list order is owned by FeatureCollection's @OrderColumn(name = "indexNo"); this property maps the
    // same physical column purely to satisfy IndexedEntity (read-only). Hibernate ORM 7 (Spring Boot 4) — unlike
    // ORM 6 — lets a writable duplicate mapping of this column shadow the @OrderColumn values on INSERT, so every
    // feature was persisted with indexNo=0 and all but one collapsed on reload. Making the property non-insertable
    // /non-updatable hands the column back to @OrderColumn so multi-feature collections persist intact.
    // Because the property is non-insertable, Hibernate OMITS the column from the Feature INSERT and writes the
    // real order via a follow-up UPDATE. The original MySQL build let the omitted NOT-NULL column default to 0
    // under sql_mode=''; PostgreSQL has no sql_mode, so the column carries an explicit DEFAULT 0 instead.
    @Column(name = "indexNo", insertable = false, updatable = false, columnDefinition = "integer default 0 not null")
    int indexNo;

    @Column(columnDefinition = "GEOMETRY", nullable = false)
    private Geometry geometry;

    @Column(name="properties", columnDefinition = "TEXT")
    @Convert(converter = JpaPropertiesAttributeConverter.class)
    private Map<String, Object> properties = new HashMap<>();

    /** Ensure that the UID is defined */
    @PrePersist
    protected void onCreate() {
        if (uid == null) {
            assignNewUid();
        }
    }

    /** Assigns a new UID to the Feature **/
    public String assignNewUid() {
        uid = UUID.randomUUID().toString();
        return uid;
    }

    /** Converts this Feature entity to GeoJson */
    public FeatureVo toGeoJson() {
        FeatureVo vo = new FeatureVo();
        vo.setGeometry(JtsConverter.fromJts(geometry));
        vo.setId(uid);
        vo.getProperties().putAll(properties);
        return vo;
    }

    /**
     * Converts a GeoJson Feature to a Feature entity. The geometry is stored on the globe: moved by whole turns
     * into range, and refused for a latitude beyond 90 (see {@link JtsConverter#toJtsOnGlobe}).
     */
    public static Feature fromGeoJson(FeatureVo vo) {
        Feature feature = new Feature();
        if (vo.getId() != null) {
            feature.setUid(vo.getId().toString());
        }
        feature.getProperties().putAll(vo.getProperties());
        feature.setGeometry(JtsConverter.toJtsOnGlobe(vo.getGeometry()));
        return feature;
    }

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public FeatureCollection getFeatureCollection() {
        return featureCollection;
    }

    public void setFeatureCollection(FeatureCollection featureCollection) {
        this.featureCollection = featureCollection;
    }

    @Override
    public int getIndexNo() {
        return indexNo;
    }

    @Override
    public void setIndexNo(int indexNo) {
        this.indexNo = indexNo;
    }

    public Geometry getGeometry() {
        return geometry;
    }

    public void setGeometry(Geometry geometry) {
        if (geometry != null) {
            geometry.setSRID(WGS84_SRID);
        }
        this.geometry = geometry;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }
}
