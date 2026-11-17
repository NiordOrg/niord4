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

import org.niord.core.db.JpaStringListAttributeConverter;
import org.niord.core.message.MessageSeries;
import org.niord.core.model.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToOne;
import java.util.List;

/**
 * Per-message-series S-124/SECOM configuration: whether the published NW messages of the
 * series are exposed as S-124 datasets over SECOM, and which languages those datasets may
 * carry. No row (or {@code exposeOverSecom = false}) means the series is never published
 * over SECOM - closed by default.
 * <p>
 * Replaces the {@code secomS124Series} setting. The per-series exposure flag is the
 * producer-side control point: one SECOM endpoint serves multiple series by design, and
 * SECOM's filter vocabulary (product/geometry/UNLOCODE/time) has no series concept, so
 * consumers cannot opt out of a series except geographically.
 * <p>
 * The language list is a restriction, not an ordering: an S-124 dataset carries the
 * message texts in every model language the message has content in, and a series that
 * must publish in fewer languages (e.g. a NAVAREA series in English only) narrows that set
 * here. An empty list (the default) restricts nothing. The codes are the two-letter model
 * language codes ({@code modelLanguages}), kept in model-language order.
 */
@Entity
@NamedQueries({
        @NamedQuery(name = "S124SeriesConfig.findAll",
                query = "SELECT c FROM S124SeriesConfig c order by c.id asc"),
        @NamedQuery(name = "S124SeriesConfig.findBySeriesId",
                query = "SELECT c FROM S124SeriesConfig c where c.messageSeries.seriesId = :seriesId"),
        @NamedQuery(name = "S124SeriesConfig.exposedSeriesIds",
                query = "SELECT c.messageSeries.seriesId FROM S124SeriesConfig c where c.exposeOverSecom = true")
})
@SuppressWarnings("unused")
public class S124SeriesConfig extends BaseEntity<Integer> {

    /** The message series this configuration applies to - one config row per series */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "series_id", nullable = false, unique = true)
    MessageSeries messageSeries;

    /** Whether published NW messages of the series are exposed as S-124 datasets over SECOM */
    @Column(nullable = false)
    boolean exposeOverSecom;

    /**
     * The model languages the S-124 datasets of the series may carry, as two-letter codes in
     * model-language order; empty (NULL) = no restriction (every model language the message
     * has content in). One comma-separated column rather than a collection table: a handful
     * of codes whose order matters.
     */
    @Convert(converter = JpaStringListAttributeConverter.class)
    @Column(length = 64)
    List<String> languages;

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public MessageSeries getMessageSeries() {
        return messageSeries;
    }

    public void setMessageSeries(MessageSeries messageSeries) {
        this.messageSeries = messageSeries;
    }

    public boolean isExposeOverSecom() {
        return exposeOverSecom;
    }

    public void setExposeOverSecom(boolean exposeOverSecom) {
        this.exposeOverSecom = exposeOverSecom;
    }

    /** The language restriction as a list of codes; empty when the series restricts nothing */
    public List<String> getLanguages() {
        return languages == null ? List.of() : languages;
    }

    /** Sets the language restriction; null or empty clears it (no restriction) */
    public void setLanguages(List<String> languages) {
        this.languages = languages;
    }
}
